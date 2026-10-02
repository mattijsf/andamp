// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track

/**
 * Which source can play a row, read from the scheme of its address: the phone's player
 * opens files, documents and stations, and any other scheme belongs to the source of that
 * name.
 */
object SourceForRows {
    /**
     * The one source that can open this row.
     *
     * A row with no scheme is the phone's: a relative path from a .m3u, or a row with no
     * uri. So is a single letter before the colon, which is a drive letter from a playlist
     * written on a PC.
     */
    fun of(track: Track): MusicSource {
        val uri = track.uri?.trim().orEmpty()
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        return when {
            scheme.length < 2 || scheme.any { !it.isLetterOrDigit() && it !in "+.-" } -> MusicSource.LOCAL
            scheme in PHONE -> MusicSource.LOCAL
            else -> MusicSource.foreign(scheme)
        }
    }

    /** What Media3 opens: documents, files, stations and the app's own assets. */
    private val PHONE = setOf("content", "file", "http", "https", "android.resource", "asset", "rtsp", "rtmp", "data")
}
