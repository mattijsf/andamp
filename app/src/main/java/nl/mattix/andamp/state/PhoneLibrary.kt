// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The phone's own music, as Preferences > Music sources > This Phone shows it: how much
 * there is, and a way to have Android look again.
 *
 * Android indexes the phone's audio and this app reads that index. A scan asks the system
 * to index the music folders now, then reads the index again so the library window shows
 * what was found.
 */
class PhoneLibrary(
    private val scope: CoroutineScope,
    private val folders: () -> List<Folder>,
    private val scanner: suspend (File) -> Unit,
    private val count: suspend () -> PhoneStats?,
    /** Called when the index may have changed, so a cached library is read again. */
    private val onChanged: () -> Unit = {},
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope = scope,
        folders = { musicFolders() },
        scanner = { folder -> askSystemToScan(context.applicationContext, folder) },
        count = { countPhone(context.applicationContext) },
        onChanged = { Libraries.forget(MusicSource.LOCAL) },
    )

    /** A folder that is scanned, and the label shown for it. */
    data class Folder(
        val label: String,
        val path: File,
    )

    /** The counts; null until counted, or when the library cannot be read. */
    var stats: PhoneStats? by mutableStateOf(null)
        private set

    /** The progress of a scan; null while none is running. */
    var scanning: ScanProgress? by mutableStateOf(null)
        private set

    /** The outcome of the last scan, in words; null before the first. */
    var lastScan: String? by mutableStateOf(null)
        private set

    /** Counts again; called when Preferences opens and when access is granted while it is open. */
    fun refresh() {
        scope.launch { stats = count() }
    }

    /**
     * Asks Android to scan every music folder, one at a time, then counts again and
     * reports what changed. A press while a scan is running does nothing.
     */
    fun scan() {
        if (scanning != null) return
        val todo = folders()
        scanning = ScanProgress(todo.firstOrNull()?.label, 0, todo.size)
        scope.launch {
            val before = stats ?: count()
            todo.forEachIndexed { done, folder ->
                scanning = ScanProgress(folder.label, done, todo.size)
                scanner(folder.path)
            }
            scanning = ScanProgress(null, todo.size, todo.size)
            val after = count()
            stats = after
            onChanged()
            lastScan = ScanProgress.outcome(before, after, todo.map { it.label })
            scanning = null
        }
    }

    companion object {
        /** How long one folder may take before the scan moves on. */
        private const val FOLDER_TIMEOUT_MS = 120_000L

        /** The standard audio folders that exist on this phone. */
        fun musicFolders(): List<Folder> =
            listOfNotNull(
                folderOf("Music", Environment.DIRECTORY_MUSIC),
                folderOf("Download", Environment.DIRECTORY_DOWNLOADS),
                folderOf("Podcasts", Environment.DIRECTORY_PODCASTS),
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    folderOf("Audiobooks", Environment.DIRECTORY_AUDIOBOOKS)
                } else {
                    null
                },
            )

        @Suppress("DEPRECATION") // the path is only handed to the media scanner; the app does not write there
        private fun folderOf(
            label: String,
            type: String,
        ): Folder? = Environment.getExternalStoragePublicDirectory(type).takeIf { it.isDirectory }?.let { Folder(label, it) }

        /**
         * Scans one folder and everything under it with the platform's scanner. The
         * callback comes when it is done; the timeout covers a path it never answers for.
         */
        private suspend fun askSystemToScan(
            context: Context,
            folder: File,
        ) {
            withTimeoutOrNull(FOLDER_TIMEOUT_MS) {
                suspendCancellableCoroutine { done ->
                    MediaScannerConnection.scanFile(context, arrayOf(folder.absolutePath), null) { _, _ ->
                        if (done.isActive) done.resume(Unit)
                    }
                }
            }
        }

        /** Counts the index; null without the permission to read it. */
        private suspend fun countPhone(context: Context): PhoneStats? =
            withContext(Dispatchers.IO) {
                val browse = MediaStoreBrowseSource(context)
                if (!browse.available) return@withContext null
                var tracks = 0
                var durationMs = 0L
                var bytes = 0L
                // access can be revoked between the check above and the query
                try {
                    tally(context) { duration, size ->
                        tracks++
                        durationMs += duration
                        bytes += size
                    }
                } catch (_: SecurityException) {
                    return@withContext null
                }
                if (!browse.available) return@withContext null
                // artists and albums are counted as the library window lists them
                PhoneStats(tracks, browse.artists().size, browse.albums(null).size, durationMs, bytes)
            }

        /** Passes every music file's duration and size to [each]. */
        private fun tally(
            context: Context,
            each: (Long, Long) -> Unit,
        ) {
            context.contentResolver
                .query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE),
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) each(cursor.getLong(0), cursor.getLong(1))
                }
        }
    }
}

/** How much music there is on the phone. */
data class PhoneStats(
    val tracks: Int,
    val artists: Int,
    val albums: Int,
    val durationMs: Long,
    val bytes: Long,
) {
    /** The row's subtitle on the main Preferences page. */
    val summary: String get() = "${count(tracks, "track")} · ${count(artists, "artist")} · ${count(albums, "album")}"

    /** The total playing time, as "3 days 4 hours" or "52 minutes". */
    val listening: String
        get() {
            val minutes = durationMs / MS_PER_MINUTE
            val hours = minutes / MINUTES_PER_HOUR
            val days = hours / HOURS_PER_DAY
            return when {
                days > 0 -> "${count(days.toInt(), "day")} ${count((hours % HOURS_PER_DAY).toInt(), "hour")}"
                hours > 0 -> "${count(hours.toInt(), "hour")} ${count((minutes % MINUTES_PER_HOUR).toInt(), "minute")}"
                else -> count(minutes.toInt(), "minute")
            }
        }

    /** The total size, as "4.2 GB" or "310 MB". */
    val size: String
        get() {
            val mb = bytes / BYTES_PER_MB.toDouble()
            return if (mb >=
                MB_PER_GB
            ) {
                String.format(Locale.US, "%.1f GB", mb / MB_PER_GB)
            } else {
                String.format(Locale.US, "%.0f MB", mb)
            }
        }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val MINUTES_PER_HOUR = 60L
        const val HOURS_PER_DAY = 24L
        const val BYTES_PER_MB = 1_048_576L
        const val MB_PER_GB = 1024.0

        fun count(
            n: Int,
            noun: String,
        ) = String.format(Locale.US, "%,d %s", n, if (n == 1) noun else "${noun}s")
    }
}

/** A scan under way: which folder, and how many of them are done. */
data class ScanProgress(
    /** The folder being scanned; null once all are done and the index is being read. */
    val folder: String?,
    val done: Int,
    val of: Int,
) {
    val fraction: Float get() = if (of == 0) 1f else done.toFloat() / of

    /** The text the page shows during the scan. */
    val words: String get() = folder?.let { "Looking in $it… ($done of $of)" } ?: "Reading what was found…"

    companion object {
        /** The text for a finished scan: which folders, and what changed. */
        fun outcome(
            before: PhoneStats?,
            after: PhoneStats?,
            looked: List<String>,
        ): String {
            val where = if (looked.isEmpty()) "No music folders on this phone" else "Looked in ${looked.joinToString(", ")}"
            if (after == null) return "$where. The library could not be read."
            val added = after.tracks - (before?.tracks ?: after.tracks)
            val found =
                when {
                    added > 0 -> if (added == 1) "1 new track" else String.format(Locale.US, "%,d new tracks", added)
                    added < 0 -> if (added == -1) "1 track fewer" else String.format(Locale.US, "%,d tracks fewer", -added)
                    else -> "nothing new"
                }
            return "$where: $found."
        }
    }
}
