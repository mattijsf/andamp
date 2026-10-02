// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Track

/**
 * A stopped station is called by its own name, not by what it last played.
 *
 * Live metadata renames a stream's row to the song while it plays. When the listening
 * stops, the row goes back to [Track.defaultName].
 */
fun BackendState.withStationsAtRest(): BackendState = copy(queue = queue.map { it.atRest() })

/** One row, put back to its own name if it is a station that was showing what it played. */
fun Track.atRest(): Track {
    val called = defaultName?.takeIf { it.isNotEmpty() }
    return if (isStream && called != null) copy(artist = "", title = called) else this
}
