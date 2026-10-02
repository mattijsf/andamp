// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import kotlinx.coroutines.test.TestScope
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.PlaybackBackendContractTest
import nl.mattix.andamp.pack.common.audio.DecoderTurn

/**
 * The stream backend against the playback contract suite.
 *
 * The fake decoder hands its bytes over on the test scope and the fake output reads them
 * there, so the time-based cases exercise the backend's byte counting. Every case runs
 * with one [DecoderTurn] for the backend, as with the real decoder.
 */
class StreamBackendTest : PlaybackBackendContractTest() {
    /** How long each row is, so the fake standing in for the decoder knows when to stop. */
    private val lengths = mutableMapOf<String, Long>()

    override fun TestScope.createBackend(tracks: List<Track>): PlaybackBackend {
        val playable = tracks.map { it.copy(uri = "${PLAYS}${it.id}") }
        playable.forEach { row -> row.uri?.let { lengths[it] = row.durationMs } }
        val turn = DecoderTurn()
        return StreamBackend(
            tracks = playable,
            scope = backgroundScope,
            open = { track, positionMs ->
                // a row without a length is a stream that does not end, like the suite's
                // station row
                FakeStreamAudio(backgroundScope, lengths[track.uri] ?: ENDLESS, turn, startMs = positionMs)
            },
            out = FakeAudioOut(backgroundScope),
        )
    }

    private companion object {
        const val PLAYS = "stream:track:"
        const val ENDLESS = 0L
    }
}
