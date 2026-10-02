// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import java.io.File
import java.net.URI
import java.util.UUID

/**
 * The listener's radio stations, kept as one .m3u file.
 *
 * A station is a [Track] (a name, a stream url, no length), so this reuses [PlaylistCodec]
 * and the file is an .m3u another player can read. Plain JVM, like [PlaylistLibrary], so
 * tests run on a temp file.
 */
class StationStore(
    private val file: File,
) {
    /** Every station, in the order they were added. */
    fun list(): List<Track> = read().orEmpty()

    /** What is stored, or null when the file is there and could not be read; see [readOrNull]. */
    private fun read(): List<Track>? =
        file.readOrNull()?.let { text ->
            runCatching { PlaylistCodec.decode(text).tracks }.getOrNull()
        }

    /**
     * Adds a station; false when [url] is not an http(s) url with a host, or the stored
     * file could not be read or written.
     */
    fun add(
        name: String,
        url: String,
    ): Boolean {
        val trimmed = url.trim()
        if (!isStreamUrl(trimmed)) return false
        val called = name.trim().ifEmpty { hostOf(trimmed) ?: trimmed }
        val station =
            Track(
                id = "station:${UUID.randomUUID()}",
                artist = "",
                title = called,
                durationMs = 0,
                uri = trimmed,
                // the station's own name, for the row when ICY metadata names no track
                defaultName = called,
                isStream = true,
            )
        val kept = read() ?: return false
        return write(kept + station)
    }

    companion object {
        fun isStreamUrl(url: String): Boolean {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            return runCatching { URI(url).host != null }.getOrDefault(false)
        }

        /** The url's host, as the name to suggest for an unnamed station. */
        fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
    }

    private fun write(stations: List<Track>): Boolean =
        runCatching { file.writeAtomically(PlaylistCodec.encode(PlaylistCodec.Saved(stations, 0))) }.isSuccess
}
