// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.TrackInfo
import nl.mattix.andamp.core.model.displayName
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.core.player.TrackInfoSource

/**
 * Winamp's Alt+3: asks a source to describe a track and shows the answer.
 *
 * The reading happens off the main thread. What a source cannot add, the queue's own row
 * supplies, so the box is never empty.
 */
class TrackInfoOps(
    private val state: WinampState,
    private val source: TrackInfoSource,
    private val scope: CoroutineScope,
) {
    /** The track a playlist menu entry acts on: the first selected row, else [row]. */
    fun trackFor(row: Int): Track? {
        val chosen = state.selectedRows.minOrNull() ?: row
        return state.playlist.getOrNull(chosen)
    }

    /**
     * Opens the box for [track] with what the queue knows (title, artist, length), then
     * replaces it with the source's fuller answer when there is one.
     */
    fun show(track: Track?) {
        if (track == null) return
        // the queue's own row is shown at once, because reading a file takes a moment; the
        // fuller answer replaces it in place
        val opened = fallback(track)
        state.trackInfo = opened
        scope.launch {
            val full =
                if (track.isStream) {
                    // a station has no file to ask
                    stationInfo(track) ?: fallback(track)
                } else {
                    source.describe(track) ?: fallback(track)
                }
            // only into the box this call opened: the listener may have closed it or opened
            // another one meanwhile
            if (state.trackInfo === opened) state.trackInfo = full
        }
    }

    /**
     * What there is to say about a station: its name, genre and website from the stream's
     * headers, what it says it is playing, its address, and the bitrate and sample rate.
     */
    private fun stationInfo(track: Track): TrackInfo? {
        val headers = state.station
        return TrackInfo.of(
            // the broadcaster's own name where it gave one, else the name the listener typed
            heading = headers?.name ?: track.defaultName?.takeIf { it.isNotEmpty() } ?: track.title,
            lines =
                listOf(
                    "Station" to (headers?.name ?: track.defaultName?.takeIf { it.isNotEmpty() }),
                    "Genre" to headers?.genre,
                    // blank until the stream sends metadata
                    "Now playing" to nowPlaying(track),
                    "Url" to track.uri,
                    "Website" to headers?.url,
                    // the track's value where there is one, else the header's
                    "Bitrate" to (track.bitrateKbps ?: headers?.bitrateKbps)?.let { "$it kbps" },
                    "Sample rate" to (state.liveSampleRateKhz(track) ?: track.sampleRateKhz)?.let { "$it kHz" },
                ),
        )
    }

    /** Artist and title as the stream gave them, or nothing while it has not. */
    private fun nowPlaying(track: Track): String? {
        val song = track.displayName.takeIf { it.isNotBlank() } ?: return null
        // before any metadata arrives the row shows the station's own name, which is not
        // repeated under "Now playing"
        return song.takeIf { it != track.defaultName }
    }

    private fun fallback(track: Track) =
        TrackInfo.of(
            heading = track.title,
            lines =
                listOf(
                    "Title" to track.title,
                    "Artist" to track.artist,
                    // durationSec is 0 for a station, which leaves the line blank
                    "Length" to
                        track.durationSec.takeIf { it > 0 }?.let { seconds ->
                            "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
                        },
                    "Bitrate" to track.bitrateKbps?.let { "$it kbps" },
                    "Sample rate" to (state.liveSampleRateKhz(track) ?: track.sampleRateKhz)?.let { "$it kHz" },
                ),
        )

    private companion object {
        const val MS_PER_SECOND = 1000
        const val SECONDS_PER_MINUTE = 60
    }
}
