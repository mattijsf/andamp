// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import nl.mattix.andamp.core.model.Track
import java.io.File

/**
 * Keeps the playlist across restarts, the way Winamp kept winamp.m3u.
 *
 * The file is written atomically, so a crash mid-save cannot leave half a playlist. A
 * `content://` uri from the document picker is good only for this process unless it is taken
 * persistably, so [remember] is called on every pick.
 */
class PlaylistStore(
    context: Context,
    /**
     * Where each source's app is handed out, by source id. It is asked on every write, so a
     * source installed while the app runs is in the next save, and written into the file, so
     * the address survives the source's app being uninstalled or the list being shared.
     */
    private val pages: (Context) -> Map<String, String> = { where ->
        PackSources.found.mapNotNull { source -> source.home(where)?.let { source.source.id to it } }.toMap()
    },
) {
    private val app = context.applicationContext
    private val resolver = context.contentResolver
    private val file = File(context.filesDir, FILE_NAME)

    /**
     * What the player starts with: the stored queue and the row it was on, or [fallback] from
     * the top when there is nothing to restore.
     */
    fun initial(fallback: List<Track>): PlaylistCodec.Saved {
        val stored = load() ?: return PlaylistCodec.Saved(fallback, 0)
        // a row for a track packaged with the app takes the artist and title this build gives it
        val packaged = fallback.filter { it.uri?.startsWith(PACKAGED) == true }.associateBy { it.uri }
        return stored.copy(
            tracks =
                stored.tracks.map { row ->
                    packaged[row.uri]?.let { row.copy(artist = it.artist, title = it.title) } ?: row
                },
        )
    }

    /**
     * Where each source in the stored playlist is handed out, as the file records it. Read
     * from the file because the source may not be installed. Empty when the file is missing
     * or records no addresses.
     */
    fun pagesKnown(): Map<String, String> = load()?.sources.orEmpty()

    /**
     * The stored queue, or null when there is nothing usable on disk.
     *
     * An empty queue the listener cleared is restored as empty. It is told apart from a
     * truncated or foreign file by the [PlaylistCodec.HEADER] line: a file with neither the
     * header nor an entry counts as nothing stored.
     */
    fun load(): PlaylistCodec.Saved? {
        if (!file.isFile) return null
        val text =
            runCatching { file.readText() }
                .onFailure { Log.w(TAG, "Could not read the stored playlist", it) }
                .getOrNull() ?: return null
        val saved = runCatching { PlaylistCodec.decode(text) }.getOrNull() ?: return null
        val ours = text.lineSequence().any { it.trim() == PlaylistCodec.HEADER }
        return saved.takeIf { ours || it.tracks.isNotEmpty() }
    }

    /**
     * Writes the queue, atomically; see [writeAtomically].
     *
     * @return true when the queue is on disk.
     */
    fun save(
        tracks: List<Track>,
        currentIndex: Int,
    ): Boolean =
        runCatching { file.writeAtomically(PlaylistCodec.encode(PlaylistCodec.Saved(tracks, currentIndex, pages(app)))) }
            .onFailure { Log.w(TAG, "Could not store the playlist", it) }
            .isSuccess

    /** LIST > SAVE LIST: the queue as an .m3u anything else can read. */
    fun export(
        out: java.io.OutputStream,
        tracks: List<Track>,
        currentIndex: Int,
    ) = out.use { it.write(PlaylistCodec.encode(PlaylistCodec.Saved(tracks, currentIndex, pages(app))).toByteArray()) }

    /** LIST > LOAD LIST. The read is bounded, so a mispicked large file fails. */
    fun import(input: java.io.InputStream): PlaylistCodec.Saved? =
        runCatching {
            val bytes = input.use { it.readAtMost(MAX_PLAYLIST_BYTES) }
            PlaylistCodec.decode(String(bytes))
        }.onFailure { Log.w(TAG, "Could not read the picked playlist", it) }
            .getOrNull()
            ?.takeIf { it.tracks.isNotEmpty() }

    /**
     * Releases the persisted grants that [uris] do not need. The platform caps how many
     * grants an app may hold, and past the cap the oldest are lost.
     */
    fun retainOnly(uris: Set<String>) {
        // a folder's grant covers the tracks underneath it; see GrantScope
        resolver.persistedUriPermissions
            .filterNot { GrantScope.isNeeded(it.uri.toString(), uris) }
            .forEach { held ->
                runCatching { resolver.releasePersistableUriPermission(held.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    .onFailure { Log.i(TAG, "Could not release ${held.uri}", it) }
            }
    }

    /** The folder behind ADD > DIR. One grant covers every file under it. */
    fun rememberTree(uri: Uri) = remember(uri)

    /**
     * Holds on to a picked document across restarts. A uri that cannot be persisted (a share
     * intent's, for one) still plays this session.
     */
    fun remember(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.i(TAG, "No lasting permission for $uri; it plays this session only", it) }
    }

    companion object {
        /** Where a track packaged with the app lives. */
        private const val PACKAGED = "asset:"

        private const val TAG = "PlaylistStore"

        /** Winamp's own name for the file, and a real .m3u for anything else that reads it. */
        const val FILE_NAME = "winamp.m3u"

        /** The most that is read of a picked playlist file. */
        const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    }
}
