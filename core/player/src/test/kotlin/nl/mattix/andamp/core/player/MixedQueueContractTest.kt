// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

import kotlinx.coroutines.test.TestScope
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.PlaybackBackendContractTest
import kotlin.random.Random

/** The composite with one player: every row is that player's, so the queue is handed over whole. */
class MixedQueueOneLaneTest : PlaybackBackendContractTest() {
    override fun TestScope.createBackend(tracks: List<Track>): PlaybackBackend =
        MixedQueueBackend(
            lanes = listOf(MixedQueueBackend.Lane(claims = { true }, make = { MockBackend(emptyList(), backgroundScope) })),
            tracks = tracks,
            startIndex = 0,
            scope = backgroundScope,
            random = Random(seed = 42),
        )
}

/**
 * The composite with rows that alternate between two players, so every row is a run of its own
 * and every advance is a handover.
 */
class MixedQueueAlternatingTest : PlaybackBackendContractTest() {
    override fun TestScope.createBackend(tracks: List<Track>): PlaybackBackend =
        MixedQueueBackend(
            lanes =
                listOf(
                    MixedQueueBackend.Lane(claims = ::even, make = { MockBackend(emptyList(), backgroundScope) }),
                    MixedQueueBackend.Lane(claims = { true }, make = { MockBackend(emptyList(), backgroundScope) }),
                ),
            tracks = tracks,
            startIndex = 0,
            scope = backgroundScope,
            random = Random(seed = 42),
        )

    // the contract's rows are t0, t1, t2...; rows with any other id go to the second lane
    private fun even(track: Track) =
        track.id
            .removePrefix("t")
            .toIntOrNull()
            ?.let { it % 2 == 0 } ?: false
}
