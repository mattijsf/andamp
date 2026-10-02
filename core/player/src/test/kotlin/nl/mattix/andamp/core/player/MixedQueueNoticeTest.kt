// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.PlaybackBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Each notice the mixed queue publishes carries a new sequence number.
 *
 * A notice is part of the state, and a state flow drops a value equal to the one before it,
 * so a repeated notice needs a new number to be seen. Which notice is raised when is covered
 * by `MixedQueueBackendTest`.
 */
class MixedQueueNoticeTest {
    /** A player that plays until the test calls [givesUp], and then stops with [BackendNotice.SourceCannotPlay]. */
    private class Source(
        private val inner: MockBackend,
    ) : PlaybackBackend by inner {
        private val reported = MutableStateFlow(inner.state.value)
        override val state: StateFlow<BackendState> = reported

        fun givesUp() {
            inner.stop()
            reported.value = inner.state.value.raising(BackendNotice.SourceCannotPlay)
        }

        override fun setQueue(
            tracks: List<Track>,
            startIndex: Int,
        ) = forward { inner.setQueue(tracks, startIndex) }

        override fun play() = forward { inner.play() }

        override fun playAt(index: Int) = forward { inner.playAt(index) }

        override fun stop() = forward { inner.stop() }

        private fun forward(verb: () -> Unit) {
            verb()
            reported.value = inner.state.value
        }
    }

    private fun row(id: String) = Track(id, "Artist", id.uppercase(), 2_000L)

    private fun TestScope.source() = Source(MockBackend(emptyList(), backgroundScope))

    /** Rows whose player cannot be built. */
    private fun TestScope.unreachable(vararg ids: String) =
        MixedQueueBackend(listOf(MixedQueueBackend.Lane({ true }, { null })), ids.map { row(it) }, 0, backgroundScope)

    @Test
    fun `a second press that finds nothing to play raises a second notice`() =
        runTest {
            val backend = unreachable("x1", "x2")
            backend.play()
            val first = backend.state.value

            backend.play()

            val second = backend.state.value
            assertEquals(BackendNotice.NothingPlayableHere, second.notice)
            assertNotEquals("the second notice changes the state", first, second)
            assertTrue(second.noticeSeq > first.noticeSeq)
        }

    @Test
    fun `a player's notice is passed on with the player's sequence number`() =
        runTest {
            val player = source()
            val lane = MixedQueueBackend.Lane({ true }, { player })
            val backend = MixedQueueBackend(listOf(lane), listOf(row("a1"), row("a2")), 0, backgroundScope)
            backend.play()
            runCurrent()

            player.givesUp()
            runCurrent()
            val first = backend.state.value
            assertEquals(BackendNotice.SourceCannotPlay, first.notice)
            assertEquals("the notice keeps the player's sequence number", player.state.value.noticeSeq, first.noticeSeq)

            backend.play()
            runCurrent()
            player.givesUp()
            runCurrent()

            assertEquals(player.state.value.noticeSeq, backend.state.value.noticeSeq)
            assertTrue("the second notice has a higher sequence number", backend.state.value.noticeSeq > first.noticeSeq)
        }

    @Test
    fun `players that all give up a second time raise a second notice`() =
        runTest {
            val a = source()
            val b = source()
            val lanes = listOf(MixedQueueBackend.Lane({ it.id.startsWith("a") }, { a }), MixedQueueBackend.Lane({ true }, { b }))
            val backend = MixedQueueBackend(lanes, listOf(row("a1"), row("b1")), 0, backgroundScope, Random(seed = 7))
            backend.setRepeat(true)
            val first = everyPlayerGivesUp(backend, a, b)
            assertEquals(BackendNotice.SourceCannotPlay, first.notice)

            val second = everyPlayerGivesUp(backend, a, b)

            assertEquals(BackendNotice.SourceCannotPlay, second.notice)
            assertTrue("the second notice has a higher sequence number", second.noticeSeq > first.noticeSeq)
        }

    /** Sequence numbers increase across every state that raises a notice. */
    @Test
    fun `a notice raised later has a higher sequence number`() {
        val earlier = BackendState().raising(BackendNotice.NothingPlayableHere)
        val later = BackendState().raising(BackendNotice.NothingPlayableHere)

        assertTrue(later.noticeSeq > earlier.noticeSeq)
    }

    /** Presses play and has each playing player give up in turn; returns the state after the last. */
    private fun TestScope.everyPlayerGivesUp(
        backend: MixedQueueBackend,
        vararg players: Source,
    ): BackendState {
        backend.play()
        runCurrent()
        repeat(players.size) {
            players.first { it.state.value.transport == Transport.Playing }.givesUp()
            runCurrent()
        }
        return backend.state.value
    }
}
