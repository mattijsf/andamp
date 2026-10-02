// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.backend.mock

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.PlaybackBackendContractTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class MockBackendTest : PlaybackBackendContractTest() {
    override fun TestScope.createBackend(tracks: List<Track>): PlaybackBackend =
        MockBackend(tracks, backgroundScope, random = Random(seed = 42))

    @Test
    fun `an enqueue while playing leaves the clock where it was`() =
        runTest {
            // an enqueue does not restart the clock, so the position is not set back
            val backend = MockBackend(listOf(Track("t0", "Artist", "Title", 10_000)), backgroundScope)
            backend.play()
            advanceTimeBy(600)
            runCurrent()

            backend.enqueue(listOf(Track("e0", "Artist", "Extra", 5_000)))
            advanceTimeBy(600)
            runCurrent()

            assertEquals(1_000L, backend.state.value.positionMs)
        }

    @Test
    fun `a queue edit the playing track survives leaves the clock where it was`() =
        runTest {
            val playing = Track("t0", "Artist", "Title", 10_000)
            val backend = MockBackend(listOf(playing), backgroundScope)
            backend.play()
            advanceTimeBy(600)
            runCurrent()

            backend.setQueue(listOf(Track("n0", "Artist", "New", 5_000), playing))
            advanceTimeBy(600)
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(1_000L, backend.state.value.positionMs)
        }
}
