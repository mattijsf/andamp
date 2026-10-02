// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.pack.common.audio.DecoderTurn
import nl.mattix.andamp.pack.common.audio.StreamAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One track into the next without losing the end of the first.
 *
 * A track change the listener did not ask for (a song finishing) continues in the stream
 * the output is reading, so nothing waiting in it is discarded. One they did ask for (a
 * skip, a pick, a seek) discards. The fake output records every discard.
 *
 * The next row is opened ahead of its turn and becomes the current one only when its
 * samples reach the output. Opening is the network, not the codec: the fakes take the
 * backend's one codec in turn and write each phase to [phases].
 */
class StreamGaplessTest {
    /** Every decoder the backend opened, in order. */
    private val opened = mutableListOf<FakeStreamAudio>()

    /** Rows that will not play at all, by id. */
    private val broken = mutableSetOf<String>()

    private lateinit var out: FakeAudioOut

    private val turn = DecoderTurn()

    /** `open`, `codec` and `free`, by row, in the order they happened, across every decoder. */
    private val phases = mutableListOf<String>()

    @Test
    fun `a track that ends by itself is followed in the same stream with nothing thrown away`() =
        runTest {
            val backend = backend(tracks(2, 10))
            backend.play()
            runCurrent()
            val discards = discards()

            advanceTimeBy(2501)
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals("the advance discards nothing", discards, discards())
            assertEquals("the output is started once", listOf("start"), out.order)
        }

    @Test
    fun `a natural advance never has two codecs at once`() =
        runTest {
            val backend = backend(tracks(6, 10))
            backend.play()
            runCurrent()

            advanceTimeBy(1101)
            runCurrent()
            assertTrue("the next row's stream opens near the end: $phases", "open t1" in phases)
            assertFalse("the next row has no codec while the first decodes: $phases", "codec t1" in phases)
            assertEquals(0, backend.state.value.currentIndex)

            val discards = discards()
            advanceTimeBy(5000)
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals("the advance discards nothing", discards, discards())
            assertEquals(
                "the next row opens early and takes the codec when the first frees it",
                listOf("open t0", "codec t0", "open t1", "free t0", "codec t1"),
                phases,
            )
            assertEquals(1, mostCodecsAtOnce())
        }

    @Test
    fun `a whole queue played through never has two codecs at once`() =
        runTest {
            val backend = backend(tracks(2, 3, 2, 10))
            backend.play()
            runCurrent()

            advanceTimeBy(8001)
            runCurrent()

            assertEquals(3, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertEquals("four codecs are taken: $phases", 4, phases.count { it.startsWith("codec") })
            assertEquals(1, mostCodecsAtOnce())
        }

    @Test
    fun `the next track is opened before the current one ends`() =
        runTest {
            val backend = backend(tracks(10, 10))
            backend.play()
            runCurrent()

            advanceTimeBy(4001)
            runCurrent()
            assertEquals("no next row is opened far from the end", 1, opened.size)

            advanceTimeBy(1100)
            runCurrent()
            assertEquals("the next row is opened near the end", 2, opened.size)
            assertEquals("the cursor stays on the playing track", 0, backend.state.value.currentIndex)
        }

    @Test
    fun `the row changes when the next track's audio arrives, not when it opens`() =
        runTest {
            val backend = backend(tracks(6, 10))
            backend.play()
            runCurrent()
            advanceTimeBy(1101)
            runCurrent()
            assertEquals(2, opened.size)

            // a server slow to send the first bytes of the next song
            opened[1].stall()
            advanceTimeBy(6000)
            runCurrent()

            val waiting = backend.state.value
            assertEquals("the cursor stays on the first track until the next one's audio arrives", 0, waiting.currentIndex)
            assertEquals(6000L, waiting.positionMs)
            assertEquals(Transport.Playing, waiting.transport)

            opened[1].unstall()
            advanceTimeBy(200)
            runCurrent()

            val arrived = backend.state.value
            assertEquals(1, arrived.currentIndex)
            assertTrue("the position counts from the top of the new track: ${arrived.positionMs}", arrived.positionMs in 1..200)
            assertTrue("no further discard happens while the next track waits", out.buffered.count { it == "discard" } == 1)
        }

    @Test
    fun `the position counts on from the first byte of the new track`() =
        runTest {
            val backend = backend(tracks(2, 10))
            backend.play()
            runCurrent()
            advanceTimeBy(1901)
            runCurrent()
            assertEquals(1900L, backend.state.value.positionMs)

            advanceTimeBy(100)
            runCurrent()
            val boundary = backend.state.value
            assertEquals(1, boundary.currentIndex)
            assertTrue(
                "the position is within one tick of the new track's start: ${boundary.positionMs}",
                boundary.positionMs in 1..100,
            )

            advanceTimeBy(1000)
            runCurrent()
            assertEquals(
                "the position advances with every byte after the boundary",
                boundary.positionMs + 1000,
                backend.state.value.positionMs,
            )
        }

    @Test
    fun `a skip discards what is waiting`() =
        runTest {
            val backend = backend(tracks(3, 10))
            backend.play()
            runCurrent()
            advanceTimeBy(1001)
            runCurrent()
            val ahead = opened[1]
            val discards = discards()

            backend.next()
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals("a skip discards once", discards + 1, discards())
            assertTrue("the row opened ahead is released", ahead.told.contains("release"))
            assertEquals("the skip opens the row again", 3, opened.size)

            advanceTimeBy(1000)
            runCurrent()
            val heard = backend.state.value.positionMs
            assertTrue("the reopened row plays: $heard", heard > 0)
            assertEquals(1, mostCodecsAtOnce())
        }

    @Test
    fun `a seek near the end lets go of the track opened to follow`() =
        runTest {
            val backend = backend(tracks(3, 10))
            backend.play()
            runCurrent()
            advanceTimeBy(1001)
            runCurrent()
            val ahead = opened[1]

            backend.seekTo(0)
            runCurrent()

            assertTrue(ahead.told.contains("release"))
            assertEquals(0, backend.state.value.currentIndex)
        }

    /**
     * In the tail of a track the decoder has finished and only what it decoded is still in
     * the pipe. A seek there is carried out by opening the track again.
     */
    @Test
    fun `a seek in the tail of a track opens it again at the new place`() =
        runTest {
            // one track alone, so a seek that was not carried out would show as a stop
            val backend = backend(tracks(5))
            backend.play()
            runCurrent()
            val first = opened[0]
            while (first.ending == null) advanceTimeBy(STEP)
            assertEquals("the tail is still playing", Transport.Playing, backend.state.value.transport)

            backend.seekTo(1000)
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()

            val state = backend.state.value
            assertEquals("the seek stays on the track", 0, state.currentIndex)
            assertEquals(Transport.Playing, state.transport)
            assertTrue("playback continues from the new place: ${state.positionMs}", state.positionMs in 1500..2500)
            assertEquals("t0", opened.last { it !== first && "start" in it.told }.label)
        }

    /** The same while paused: moved, and still paused. */
    @Test
    fun `a seek in the tail while paused stays paused`() =
        runTest {
            val backend = backend(tracks(5))
            backend.play()
            runCurrent()
            val first = opened[0]
            while (first.ending == null) advanceTimeBy(STEP)
            backend.pause()
            runCurrent()

            backend.seekTo(1000)
            runCurrent()
            assertEquals(Transport.Paused, backend.state.value.transport)
            assertEquals(1000, backend.state.value.positionMs)

            // play carries on from the new place
            backend.play()
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()

            val state = backend.state.value
            assertEquals(0, state.currentIndex)
            assertEquals(Transport.Playing, state.transport)
            assertTrue("playback continues from the new place: ${state.positionMs}", state.positionMs in 1500..2500)
        }

    @Test
    fun `a broken track after a natural end is skipped without stopping`() =
        runTest {
            broken += "t1"
            val backend = backend(tracks(2, 2, 10))
            backend.play()
            runCurrent()
            val discards = discards()

            advanceTimeBy(3001)
            runCurrent()

            val state = backend.state.value
            assertEquals("the queue moves past the broken track", 2, state.currentIndex)
            assertEquals(Transport.Playing, state.transport)
            assertNull(state.notice)
            assertEquals("the track before the broken one ends without a discard", discards, discards())
            assertEquals("the broken row is opened once", 3, opened.size)
            assertFalse("the broken row never takes a codec: $phases", phases.any { it.endsWith(" t1") })
        }

    @Test
    fun `stop after current plans nothing to follow`() =
        runTest {
            val backend = backend(tracks(2, 10))
            backend.setStopAfterCurrent(true)
            backend.play()
            runCurrent()

            advanceTimeBy(2501)
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(1, opened.size)
        }

    private fun discards(): Int = out.buffered.count { it == "discard" }

    /** The most codecs that were alive together, read off [phases]. */
    private fun mostCodecsAtOnce(): Int =
        phases
            .runningFold(0) { alive, phase ->
                when {
                    phase.startsWith("codec") -> alive + 1
                    phase.startsWith("free") -> alive - 1
                    else -> alive
                }
            }.max()

    private fun tracks(vararg durationsSec: Int): List<Track> =
        durationsSec.mapIndexed { at, sec -> Track("t$at", "Artist $at", "Title $at", sec * 1000L, uri = "${PLAYS}t$at") }

    /** A backend over fakes, with [opened] and [out] as the window into what it did. */
    private fun TestScope.backend(tracks: List<Track>): StreamBackend {
        out = FakeAudioOut(backgroundScope)
        return StreamBackend(
            tracks = tracks,
            scope = backgroundScope,
            open = { track, positionMs -> decoder(track, positionMs) },
            out = out,
        )
    }

    private fun TestScope.decoder(
        row: Track,
        positionMs: Long,
    ): StreamAudio {
        val made =
            FakeStreamAudio(
                backgroundScope,
                durationMs = row.durationMs,
                turn = turn,
                startMs = positionMs,
                broken = if (row.id in broken) "no such song on this server" else null,
                label = row.id,
                log = phases,
            )
        opened += made
        return made
    }

    private companion object {
        /** Finer than the fake decoder's tick, so a loop stops inside the tail it grants. */
        const val STEP = 10L

        const val PLAYS = "stream:track:"
    }
}
