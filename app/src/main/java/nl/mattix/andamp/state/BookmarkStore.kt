// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import java.io.File

/**
 * The listener's bookmarks, kept as one .m3u file.
 *
 * A bookmark is a [Track], stored the way a station is ([PlaylistCodec]), so the file is an
 * .m3u another player can read. Plain JVM, like [StationStore] and [PlaylistLibrary], so
 * tests run against a temp file.
 */
class BookmarkStore(
    private val file: File,
) {
    /**
     * Every bookmark, oldest first. Rows whose uri has no scheme are dropped, since a
     * hand-edited or foreign .m3u can decode into rows that cannot be opened.
     */
    fun list(): List<Track> = read().orEmpty()

    /**
     * The uris the bookmarks name, so the access they need is kept; null when the file is
     * there and could not be read.
     */
    fun uris(): Set<String>? = read()?.mapNotNullTo(mutableSetOf()) { it.uri }

    /** What is stored, or null when the file is there and could not be read; see [readOrNull]. */
    private fun read(): List<Track>? =
        file
            .readOrNull()
            ?.let { text -> runCatching { PlaylistCodec.decode(text).tracks }.getOrNull() }
            ?.filter { it.uri?.let(::isSomewhere) == true }

    /**
     * Bookmarks [track]; false when it has no usable uri, when the same uri is already
     * bookmarked, or when the file could not be read or written.
     */
    fun add(track: Track?): Boolean {
        val where = track?.uri?.trim().orEmpty()
        if (!isSomewhere(where)) return false
        val kept = read()
        if (kept == null || kept.any { it.uri == where }) return false
        return write(kept + track!!.copy(uri = where))
    }

    fun removeAt(at: Int): Boolean {
        val kept = read() ?: return false
        if (at !in kept.indices) return false
        return write(kept.filterIndexed { index, _ -> index != at })
    }

    /** Winamp's Edit: a bookmark keeps its place and takes a new name. */
    fun rename(
        at: Int,
        name: String,
    ): Boolean {
        val kept = read() ?: return false
        val called = name.trim()
        if (at !in kept.indices || called.isEmpty()) return false
        return write(kept.mapIndexed { index, track -> if (index == at) track.named(called) else track })
    }

    /** A renamed bookmark has an empty artist, so the playlist's "artist - title" shows the typed name alone. */
    private fun Track.named(what: String) = copy(artist = "", title = what)

    private fun write(bookmarks: List<Track>): Boolean =
        runCatching { file.writeAtomically(PlaylistCodec.encode(PlaylistCodec.Saved(bookmarks, 0))) }.isSuccess

    private companion object {
        /** A place has a scheme: content://, file://, https://, asset:///. */
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        fun isSomewhere(uri: String) = SCHEME.containsMatchIn(uri)
    }
}
