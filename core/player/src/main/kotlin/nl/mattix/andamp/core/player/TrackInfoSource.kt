// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.TrackInfo
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend

/**
 * Describes a track, as far as the source can.
 *
 * A contract of its own, apart from [PlaybackBackend] and [BrowseSource]: the row a listener
 * asks about may have come from a document picker that no browse source has seen.
 *
 * A source reports what it knows in [TrackInfo]. A local file yields format, bitrate, sample
 * rate and its tags; a remote source may know only a title, an artist and an album. A source
 * that knows nothing about the track returns null, and the caller falls back to what the queue
 * holds.
 */
fun interface TrackInfoSource {
    /** What it knows, or null when it has nothing to add. */
    suspend fun describe(track: Track): TrackInfo?

    companion object {
        /** Describes nothing, so the queue's own data is shown. */
        val None = TrackInfoSource { null }
    }
}
