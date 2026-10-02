// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.pack.common.audio.DecoderTurn
import nl.mattix.andamp.pack.common.audio.StreamAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the stream backend does beyond the playback contract: a track that will not play, a
 * position counted from bytes, and a decoder that is started and released at the right
 * moments.
 */
class StreamQueueTest {
    /** Every decoder the backend opened, in order, so a test can see what it was told. */
    private val opened = mutableListOf<FakeStreamAudio>()

    /** Rows that will not play at all, by id. */
    private val broken = mutableSetOf<String>()

    private lateinit var out: FakeAudioOut

    /** The backend's one codec at a time, which every fake it opens waits its turn for. */
    private val turn = DecoderTurn()

    @Test
    fun `a track that will not play does not stop the queue`() =
        runTest {
            broken += "t0"
            val backend = backend(tracks(10, 10))

            backend.play()
            runCurrent()

            val state = backend.state.value
            assertEquals("the queue moves past the broken track", 1, state.currentIndex)
            assertEquals(Transport.Playing, state.transport)
            assertNull("one broken track raises no notice", state.notice)
        }

    @Test
    fun `a queue that will not play anywhere stops with a notice`() =
        runTest {
            broken += setOf("t0", "t1")
            val backend = backend(tracks(10, 10))

            backend.play()
            // a tick for each row: the second is opened in the stream the first was in
            advanceTimeBy(201)
            runCurrent()

            val state = backend.state.value
            assertEquals(Transport.Stopped, state.transport)
            assertEquals(
                "a pass with nothing played raises SourceCannotPlay",
                BackendNotice.SourceCannotPlay,
                state.notice,
            )
            assertTrue("the notice has a sequence number", state.noticeSeq > 0)
        }

    @Test
    fun `a row the pack cannot open at all is skipped like a broken one`() =
        runTest {
            // a row with no uri has no stream URL, which is treated like a stream that
            // would not open
            val backend = backend(listOf(Track("elsewhere", "A", "B", 10_000, uri = null)) + tracks(10))

            backend.play()
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `audio clears what a run of failures said`() =
        runTest {
            broken += setOf("t0")
            val backend = backend(tracks(10))
            backend.play()
            runCurrent()
            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)

            // the listener presses play again, and this time the server answers
            broken.clear()
            backend.play()
            runCurrent()
            advanceTimeBy(1001)
            runCurrent()

            assertEquals(Transport.Playing, backend.state.value.transport)
            assertEquals(1000L, backend.state.value.positionMs)
            assertNull("audio clears the notice", backend.state.value.notice)
        }

    @Test
    fun `the end of a track lets its decoder go and opens the next`() =
        runTest {
            val backend = backend(tracks(2, 10))
            backend.play()
            runCurrent()

            advanceTimeBy(2001)
            runCurrent()

            assertEquals(2, opened.size)
            assertTrue("the finished decoder is released", opened[0].told.contains("release"))
            assertEquals(1, backend.state.value.currentIndex)
        }

    @Test
    fun `a seek moves the position before a byte has crossed`() =
        runTest {
            val backend = backend(tracks(60))
            backend.play()
            runCurrent()
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(1000L, backend.state.value.positionMs)

            backend.seekTo(30_000)

            assertEquals("the position moves to the seek target at once", 30_000L, backend.state.value.positionMs)
            assertTrue("the decoder is told to seek", opened[0].told.contains("seekTo(30000)"))
            assertTrue("the output discards audio decoded for the old position", out.buffered.contains("discard"))
        }

    @Test
    fun `the position counts on from where a seek put it`() =
        runTest {
            val backend = backend(tracks(60))
            backend.play()
            runCurrent()
            backend.seekTo(30_000)
            runCurrent()

            advanceTimeBy(1001)
            runCurrent()

            assertEquals(31_000L, backend.state.value.positionMs)
        }

    @Test
    fun `a stall does not move the readout`() =
        runTest {
            // a server that has stopped sending hands nothing over, so the byte-counted
            // position stands still
            val backend = backend(tracks(60))
            backend.play()
            runCurrent()
            advanceTimeBy(1001)
            runCurrent()

            opened[0].stall()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(1000L, backend.state.value.positionMs)
            assertEquals("a stall keeps the transport playing", Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `shuffle never runs out of queue at the last row`() =
        runTest {
            val backend = backend(tracks(20, 20, 2))
            backend.setShuffle(true)
            // the last row, which without shuffle would be the end of the queue
            backend.playAt(2)
            runCurrent()

            advanceTimeBy(2001)
            runCurrent()

            val state = backend.state.value
            assertEquals(Transport.Playing, state.transport)
            assertTrue(state.currentIndex in state.queue.indices)
        }

    @Test
    fun `stop after current stops on the track it was set on and lets the decoder go`() =
        runTest {
            val backend = backend(tracks(2, 20))
            backend.play()
            backend.setStopAfterCurrent(true)
            runCurrent()

            advanceTimeBy(2001)
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals("the cursor stays on the track that finished", 0, backend.state.value.currentIndex)
            assertEquals("no other decoder is opened", 1, opened.size)
        }

    @Test
    fun `repeat opens the head of the queue again`() =
        runTest {
            val backend = backend(tracks(2, 2))
            backend.setRepeat(true)
            backend.play()
            runCurrent()

            advanceTimeBy(4001)
            runCurrent()

            assertEquals(0, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
            // four opens: the head, the second row as the head neared its end, the head
            // again, and the second again behind it
            assertEquals(4, opened.size)
            assertTrue("repeat opens a new decoder for the head", opened[2] !== opened[0])
        }

    @Test
    fun `a stop lets the decoder go`() =
        runTest {
            val backend = backend(tracks(10))
            backend.play()
            runCurrent()

            backend.stop()
            runCurrent()

            assertTrue(opened[0].told.contains("release"))
            assertTrue("the output is stopped", out.order.contains("stop"))
        }

    private fun tracks(vararg durationsSec: Int): List<Track> =
        durationsSec.mapIndexed { at, sec -> Track("t$at", "Artist $at", "Title $at", sec * 1000L, uri = "${PLAYS}t$at") }

    /** A backend over fakes, with [opened] and [out] as the window into what it did. */
    private fun TestScope.backend(tracks: List<Track>): StreamBackend {
        val lengths = tracks.mapNotNull { row -> row.uri?.let { it to row } }.toMap()
        out = FakeAudioOut(backgroundScope)
        return StreamBackend(
            tracks = tracks,
            scope = backgroundScope,
            open = { track, positionMs -> decoder(lengths[track.uri], positionMs) },
            out = out,
            random = Random(SEED),
        )
    }

    private fun TestScope.decoder(
        row: Track?,
        positionMs: Long,
    ): StreamAudio? {
        val playable = row ?: return null
        val made =
            FakeStreamAudio(
                backgroundScope,
                durationMs = playable.durationMs,
                turn = turn,
                startMs = positionMs,
                broken = if (playable.id in broken) "no such song on this server" else null,
            )
        opened += made
        return made
    }

    private companion object {
        const val PLAYS = "stream:track:"

        /** Any seed: the cases here assert that shuffle lands somewhere, not where. */
        const val SEED = 7
    }
}
