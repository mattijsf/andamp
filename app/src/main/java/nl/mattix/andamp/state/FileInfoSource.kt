// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.TrackInfo
import nl.mattix.andamp.core.player.TrackInfoSource
import java.util.Locale

/**
 * Winamp's Alt+3 for a file on this phone: what the container says, and what the tags say.
 * It is read on demand and not stored. See [TrackInfoSource].
 */
class FileInfoSource(
    private val app: Application,
    /** Where the file is opened and parsed. */
    private val io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    private val open: () -> MediaMetadataRetriever = { MediaMetadataRetriever() },
    /**
     * The rate the player reports for [Track] while it is the one playing, or
     * null; see [WinampState.liveSampleRateKhz].
     */
    private val liveSampleRateKhz: (Track) -> Int? = { null },
    /** The uri to open a row's file under right now; see [ReadableUri]. */
    private val readable: (String) -> String = { it },
) : TrackInfoSource {
    override suspend fun describe(track: Track): TrackInfo? {
        // a stream has no file to open, and a row with no uri has nothing at all
        val from = track.uri?.takeIf { it.isNotBlank() && !isRemote(it) } ?: return null
        return withContext(io) { read(Uri.parse(from), track) }
    }

    // MediaMetadataRetriever throws RuntimeException subclasses on unreadable files
    @Suppress("TooGenericExceptionCaught")
    private fun read(
        uri: Uri,
        track: Track,
    ): TrackInfo? {
        val retriever = open()
        val tags: Map<Int, String> =
            try {
                retriever.setDataSource(app, Uri.parse(readable(uri.toString())))
                KEYS.mapNotNull { key -> retriever.extractMetadata(key)?.let { key to it } }.toMap()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read $uri", e)
                emptyMap()
            } finally {
                runCatching { retriever.release() }
            }

        val name = app.displayNameOf(uri)
        return TrackInfo.of(
            heading = tags[MediaMetadataRetriever.METADATA_KEY_TITLE] ?: name ?: track.title,
            lines =
                listOf(
                    // the container first
                    "File" to name,
                    "Size" to sizeOf(uri),
                    "Format" to tags[MediaMetadataRetriever.METADATA_KEY_MIMETYPE],
                    "Length" to lengthOf(tags, track),
                    "Bitrate" to
                        tags[MediaMetadataRetriever.METADATA_KEY_BITRATE]?.toLongOrNull()?.let { "${it / BITS_PER_KBIT} kbps" },
                    "Sample rate" to sampleRateOf(tags, track),
                    // then the tags
                    "Title" to tags[MediaMetadataRetriever.METADATA_KEY_TITLE],
                    "Artist" to tags[MediaMetadataRetriever.METADATA_KEY_ARTIST],
                    "Album" to tags[MediaMetadataRetriever.METADATA_KEY_ALBUM],
                    "Album artist" to tags[MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST],
                    "Track" to tags[MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER],
                    "Disc" to tags[MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER],
                    "Year" to (tags[MediaMetadataRetriever.METADATA_KEY_YEAR] ?: tags[MediaMetadataRetriever.METADATA_KEY_DATE]),
                    "Genre" to tags[MediaMetadataRetriever.METADATA_KEY_GENRE],
                    "Composer" to tags[MediaMetadataRetriever.METADATA_KEY_COMPOSER],
                ),
        )
    }

    /** The tag's own length, else the length the queue has for the row. */
    private fun lengthOf(
        tags: Map<Int, String>,
        track: Track,
    ): String? {
        val ms = tags[MediaMetadataRetriever.METADATA_KEY_DURATION]?.toLongOrNull() ?: track.durationMs
        if (ms <= 0) return null
        val seconds = ms / MS_PER_SECOND
        return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    }

    /**
     * The rate the player reports while this track plays, else the container's (the
     * metadata key exists from API 31), else the rate the queue has for the row.
     *
     * There is no channel count: MediaMetadataRetriever has no key for it.
     */
    private fun sampleRateOf(
        tags: Map<Int, String>,
        track: Track,
    ): String? {
        val hz = tags[MediaMetadataRetriever.METADATA_KEY_SAMPLERATE]?.toIntOrNull()
        val khz =
            liveSampleRateKhz(track)?.toFloat()
                ?: hz?.let { it / HZ_PER_KHZ }
                ?: track.sampleRateKhz?.toFloat()
        return khz?.let { "%.1f kHz".format(Locale.US, it) }
    }

    private fun sizeOf(uri: Uri): String? =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
            ?.let { bytes -> "%.1f MB".format(Locale.US, bytes / BYTES_PER_MB) }

    private fun isRemote(uri: String) = uri.startsWith("http://") || uri.startsWith("https://")

    private companion object {
        const val TAG = "FileInfoSource"
        const val BITS_PER_KBIT = 1000
        const val MS_PER_SECOND = 1000
        const val SECONDS_PER_MINUTE = 60
        const val HZ_PER_KHZ = 1000f
        const val BYTES_PER_MB = 1024f * 1024f

        val KEYS =
            listOf(
                MediaMetadataRetriever.METADATA_KEY_TITLE,
                MediaMetadataRetriever.METADATA_KEY_ARTIST,
                MediaMetadataRetriever.METADATA_KEY_ALBUM,
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER,
                MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER,
                MediaMetadataRetriever.METADATA_KEY_YEAR,
                MediaMetadataRetriever.METADATA_KEY_DATE,
                MediaMetadataRetriever.METADATA_KEY_GENRE,
                MediaMetadataRetriever.METADATA_KEY_COMPOSER,
                MediaMetadataRetriever.METADATA_KEY_DURATION,
                MediaMetadataRetriever.METADATA_KEY_BITRATE,
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE,
                MediaMetadataRetriever.METADATA_KEY_SAMPLERATE,
            )
    }
}
