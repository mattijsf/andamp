// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.StreamReconnect
import nl.mattix.andamp.pack.common.audio.DecoderTurn
import nl.mattix.andamp.pack.common.audio.Ending
import nl.mattix.andamp.pack.common.audio.StreamAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A song whose connection drops, picked up where the listener had heard it to.
 *
 * The fakes lose their connection the two ways a real decoder does (as a drop, or as an
 * early finish), and a row can be made unreachable so that a try drops again at once. The
 * network is a fake that can go and come back, and the budget runs on the test's clock.
 */
class StreamResumeTest {
    /** Every decoder the backend opened, in order, and the position each was asked to start from. */
    private val opened = mutableListOf<FakeStreamAudio>()
    private val openedAt = mutableListOf<Long>()

    /** Rows whose server cannot be reached right now, by id: opening one drops at once. */
    private val down = mutableSetOf<String>()

    /** Rows broken for good, by id. */
    private val broken = mutableSetOf<String>()

    /** Whether the rows the backend opens can be sought in; false is a transcode. */
    private var seekable = true

    private val network = FakeNetwork()
    private val turn = DecoderTurn()
    private lateinit var out: FakeAudioOut

    @Test
    fun `a song that drops mid-way is picked up on its own row, from where it was heard`() =
        runTest {
            val backend = backend(tracks(60, 60))
            backend.play()
            runCurrent()
            advanceTimeBy(3001)
            runCurrent()
            val heard = opened[0].handedMs
            val discards = discards()

            opened[0].lose()
            advanceTimeBy(READ_MS)
            runCurrent()

            val waiting = backend.state.value
            assertEquals("the cursor stays on the dropped row", 0, waiting.currentIndex)
            assertEquals("the transport stays playing", Transport.Playing, waiting.transport)
            assertTrue("the state reports connecting", waiting.connecting)
            assertEquals("no retry is made in the same instant", 1, opened.size)

            advanceTimeBy(FIRST_TRY_MS)
            runCurrent()

            assertEquals(2, opened.size)
            assertEquals("the retry opens the same row", "t0", opened[1].label)
            assertEquals("the retry starts at the position the output's bytes reached", listOf(0L, heard), openedAt)

            advanceTimeBy(1000)
            runCurrent()

            val back = backend.state.value
            assertFalse("the state stops reporting connecting", back.connecting)
            assertEquals(0, back.currentIndex)
            assertTrue(
                "the position counts on from the retry point: ${back.positionMs}",
                back.positionMs in heard + 300..heard + 2250,
            )
            assertEquals("the pick-up discards nothing", discards, discards())
            assertFalse("the network is no longer watched", network.watching)
        }

    /** The platform's extractor usually reports a connection lost under it as the end of the file. */
    @Test
    fun `a finish that comes long before the row's length is a drop too`() =
        runTest {
            val backend = backend(tracks(60))
            backend.play()
            runCurrent()
            advanceTimeBy(20_001)
            runCurrent()

            opened[0].lose(Ending.Finished)
            runCurrent()
            advanceTimeBy(FIRST_TRY_MS)
            runCurrent()

            assertEquals(Transport.Playing, backend.state.value.transport)
            assertEquals(listOf(0L, 20_000L), openedAt)
        }

    /**
     * A transcode cannot be sought, so it is asked for again with the offset in the
     * request, rounded up to the next whole second.
     */
    @Test
    fun `a transcode is picked up with its offset, rounded up to the next whole second`() =
        runTest {
            seekable = false
            val backend = backend(tracks(60))
            backend.play()
            runCurrent()
            advanceTimeBy(2501)
            runCurrent()

            opened[0].lose()
            advanceTimeBy(READ_MS)
            runCurrent()
            advanceTimeBy(FIRST_TRY_MS)
            runCurrent()

            assertEquals(listOf(0L, 3000L), openedAt)
            assertFalse(
                "the retried decoder is told no seek: ${opened[1].told}",
                opened[1].told.any { it.startsWith("seekTo") },
            )
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(
                "the position counts from the offset: ${backend.state.value.positionMs}",
                backend.state.value.positionMs in 3300..5250,
            )
        }

    @Test
    fun `a track broken for good is still skipped at once`() =
        runTest {
            val backend = backend(tracks(60, 60))
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()

            opened[0].lose(Ending.Broke("the codec gave up"))
            advanceTimeBy(READ_MS)
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertFalse(backend.state.value.connecting)
            assertEquals("the broken row is not opened again", listOf("t0", "t1"), opened.map { it.label })
        }

    @Test
    fun `a pause while waiting calls the pick-up off, and play picks it up from the same place`() =
        runTest {
            val backend = backend(tracks(60))
            backend.play()
            runCurrent()
            advanceTimeBy(4001)
            runCurrent()
            opened[0].lose()
            advanceTimeBy(READ_MS)
            runCurrent()

            backend.pause()
            runCurrent()
            advanceTimeBy(10 * 60_000L)
            runCurrent()

            assertEquals("no retry is made while paused", 1, opened.size)
            assertFalse(backend.state.value.connecting)
            assertFalse("the network is not watched while paused", network.watching)
            assertEquals(Transport.Paused, backend.state.value.transport)

            backend.play()
            runCurrent()

            assertEquals(listOf(0L, 4000L), openedAt)
        }

    @Test
    fun `with no network nothing is tried until one is back, and then once`() =
        runTest {
            val backend = backend(tracks(60))
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()

            network.online = false
            opened[0].lose()
            advanceTimeBy(READ_MS)
            runCurrent()
            advanceTimeBy(30 * 60_000L)
            runCurrent()

            assertEquals("no retry is made while offline", 1, opened.size)
            assertTrue(network.watching)
            assertTrue(backend.state.value.connecting)

            network.arrive()
            runCurrent()
            advanceTimeBy(StreamReconnect.SETTLE_MS - 50)
            runCurrent()
            assertEquals("no retry is made before the network settles", 1, opened.size)

            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, opened.size)
            advanceTimeBy(1000)
            runCurrent()
            assertFalse(backend.state.value.connecting)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `a server that does not come back is skipped, and the listener is told`() =
        runTest {
            // the next row long enough to still be playing when the minutes are up
            val backend = backend(tracks(60, 3600))
            val seen = record(backend)
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()

            down += "t0"
            opened[0].lose()
            advanceTimeBy(READ_MS)
            runCurrent()
            advanceTimeBy(10 * 60_000L)
            runCurrent()

            val tries = opened.count { it.label == "t0" } - 1
            assertEquals("the row is retried the maximum number of times", StreamReconnect.MAX_ATTEMPTS, tries)
            val told = seen.firstOrNull { it.notice == BackendNotice.ServerLost }
            assertTrue("ServerLost is raised: ${seen.map { it.notice }.distinct()}", told != null)
            assertEquals("the notice is raised as the row is skipped", 1, told?.currentIndex)
            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertFalse(backend.state.value.connecting)
        }

    /** On a server that stays away, the rows after the first are given up on at once, and the notice is raised once. */
    @Test
    fun `a server that stays away ends the list with one notice, not one a row`() =
        runTest {
            val backend = backend(tracks(60, 60, 60))
            val seen = record(backend)
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()

            down += setOf("t0", "t1", "t2")
            opened[0].lose()
            advanceTimeBy(READ_MS)
            runCurrent()
            advanceTimeBy(10 * 60_000L)
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(BackendNotice.ServerLost, backend.state.value.notice)
            assertEquals(
                "the notice is raised once",
                1,
                seen
                    .map { it.noticeSeq }
                    .filter { it > 0 }
                    .distinct()
                    .size,
            )
            assertEquals("the next row is opened once", 1, opened.count { it.label == "t1" })
        }

    /**
     * Thirty seconds of music between two drops resets the budget. The wait shows it:
     * without the reset the backoff would continue from where the first drop left it.
     */
    @Test
    fun `a song that played on long enough earns its budget back`() =
        runTest {
            val backend = backend(tracks(300))
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()

            // four tries that fail, which puts the next wait at sixteen seconds
            down += "t0"
            opened[0].lose()
            advanceTimeBy(READ_MS)
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            down.clear()
            advanceTimeBy(40_000)
            runCurrent()
            assertFalse("the stream is back", backend.state.value.connecting)

            advanceTimeBy(31_000)
            runCurrent()
            val before = opened.size
            opened.last().lose()
            advanceTimeBy(READ_MS)
            runCurrent()
            advanceTimeBy(FIRST_TRY_MS)
            runCurrent()

            assertEquals("the retry comes after the first delay", before + 1, opened.size)
        }

    /**
     * The track behind the one playing is opened seconds early and can lose its connection
     * while it waits. When it is reached it is picked up from its top and not skipped, and
     * nothing of the song before it is discarded.
     */
    @Test
    fun `a next track that lost its connection while it waited is picked up once it is reached`() =
        runTest {
            val backend = backend(tracks(6, 60))
            backend.play()
            runCurrent()
            advanceTimeBy(1101)
            runCurrent()
            assertEquals("the next row is opened ahead", listOf("t0", "t1"), opened.map { it.label })
            val discards = discards()

            opened[1].lose()
            advanceTimeBy(5000)
            runCurrent()

            assertEquals("the reached row is the current row", 1, backend.state.value.currentIndex)
            assertTrue(backend.state.value.connecting)
            assertEquals("the end of the first track is not discarded", discards, discards())

            advanceTimeBy(FIRST_TRY_MS)
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()

            assertEquals(listOf("t0", "t1", "t1"), opened.map { it.label })
            assertEquals("the next row is reopened from its top", 0L, openedAt.last())
            assertEquals(1, backend.state.value.currentIndex)
            assertFalse(backend.state.value.connecting)
            assertTrue(backend.state.value.positionMs > 0)
        }

    @Test
    fun `a seek while waiting opens the row at the new place at once`() =
        runTest {
            val backend = backend(tracks(60))
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()
            opened[0].lose()
            advanceTimeBy(READ_MS)
            runCurrent()

            backend.seekTo(40_000)
            runCurrent()

            assertEquals(listOf(0L, 40_000L), openedAt)
            assertFalse(backend.state.value.connecting)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("the replaced wait makes no further retry", 2, opened.size)
        }

    /** A pick-up opened past where the file really ends (the row's length was wrong) is the end of the song. */
    @Test
    fun `a pick-up that finds nothing left is the end of the song`() =
        runTest {
            val backend = backend(tracks(60, 60))
            // the file is really twenty seconds, whatever the server said
            realLengthMs = 20_000
            backend.play()
            runCurrent()
            advanceTimeBy(20_001)
            runCurrent()
            advanceTimeBy(FIRST_TRY_MS + 1000)
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertNull(backend.state.value.notice)
        }

    /** When non-zero, how long the first row's audio really is, whatever its length says. */
    private var realLengthMs = 0L

    private fun discards() = out.buffered.count { it == "discard" }

    private fun tracks(vararg durationsSec: Int): List<Track> =
        durationsSec.mapIndexed { at, sec -> Track("t$at", "Artist $at", "Title $at", sec * 1000L, uri = "stream:track:t$at") }

    /** Every state the backend publishes, so that a notice that was raised and then cleared is still seen. */
    private fun TestScope.record(backend: StreamBackend): List<BackendState> {
        val seen = mutableListOf<BackendState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { backend.state.collect { seen += it } }
        return seen
    }

    private fun TestScope.backend(tracks: List<Track>): StreamBackend {
        out = FakeAudioOut(backgroundScope)
        return StreamBackend(
            tracks = tracks,
            scope = backgroundScope,
            open = { track, positionMs -> decoder(track, positionMs) },
            out = out,
            random = Random(SEED),
            network = network,
            clock = { testScheduler.currentTime },
        )
    }

    private fun TestScope.decoder(
        row: Track,
        positionMs: Long,
    ): StreamAudio {
        val real = if (realLengthMs > 0 && row.id == "t0") realLengthMs else row.durationMs
        val made =
            FakeStreamAudio(
                backgroundScope,
                durationMs = real,
                turn = turn,
                startMs = positionMs,
                broken = if (row.id in broken) "no such song" else null,
                unreachable = row.id in down,
                label = row.id,
                seekable = seekable,
            )
        opened += made
        openedAt += positionMs
        return made
    }

    /** A network that can go and come back, and says whether anybody is listening to it. */
    private class FakeNetwork : NetworkWatch {
        override var online = true

        var watching = false
            private set

        private var told: ((Boolean) -> Unit)? = null

        /** A validated network arrives. */
        fun arrive() {
            online = true
            told?.invoke(true)
        }

        override fun watch(onChange: (online: Boolean) -> Unit) {
            watching = true
            told = onChange
        }

        override fun unwatch() {
            watching = false
            told = null
        }
    }

    private companion object {
        /** Past the first wait at its longest: a second, and the jitter's fifth of it. */
        const val FIRST_TRY_MS = 1_250L

        const val SEED = 11

        /** One read of the fake output and a moment: long enough for a drop to be read and noticed. */
        const val READ_MS = 101L
    }
}
