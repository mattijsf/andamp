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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stream the server produces as it sends it, and seeking in it.
 *
 * A transcode arrives as a 200 with no byte ranges, so the decoder cannot seek. The backend
 * asks for the row again with the new position, which the pack puts in the request, and
 * counts from there.
 *
 * The fakes behave as the real decoder does over such a stream: a seek is recorded and
 * changes nothing.
 */
class StreamUnseekableTest {
    /** Every decoder the backend opened, in order, and the position each was asked to start from. */
    private val opened = mutableListOf<FakeStreamAudio>()
    private val openedAt = mutableListOf<Long>()

    /** Whether the rows the backend opens can be sought in; see [StreamAudio.seekable]. */
    private var seekable = false

    private val turn = DecoderTurn()

    @Test
    fun `a seek in a stream that cannot be sought opens the row again at the new place`() =
        runTest {
            val backend = backend(tracks(20))
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()
            val first = opened.single()

            backend.seekTo(10_000)
            runCurrent()

            assertFalse("the first decoder is told no seek: ${first.told}", first.told.any { it.startsWith("seekTo") })
            assertTrue("the first decoder is released", first.told.contains("release"))
            assertEquals("the row is opened again at the new position", listOf(0L, 10_000L), openedAt)
            assertEquals(10_000, backend.state.value.positionMs)

            advanceTimeBy(1000)
            runCurrent()

            val state = backend.state.value
            assertEquals(0, state.currentIndex)
            assertEquals(Transport.Playing, state.transport)
            assertTrue("the position counts on from the new place: ${state.positionMs}", state.positionMs in 10_500..11_500)
        }

    @Test
    fun `play pressed again opens a stream that cannot be sought from the top`() =
        runTest {
            val backend = backend(tracks(20))
            backend.play()
            runCurrent()
            advanceTimeBy(3001)
            runCurrent()

            backend.play()
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()

            assertEquals(listOf(0L, 0L), openedAt)
            assertFalse(opened.first().told.any { it.startsWith("seekTo") })
            assertTrue(
                "the position counts from the top: ${backend.state.value.positionMs}",
                backend.state.value.positionMs in 500..1500,
            )
        }

    @Test
    fun `a seek while paused opens the row at the new place and stays paused`() =
        runTest {
            val backend = backend(tracks(20))
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()
            backend.pause()
            runCurrent()

            backend.seekTo(8_000)
            runCurrent()

            assertEquals(Transport.Paused, backend.state.value.transport)
            assertEquals(8_000, backend.state.value.positionMs)
            assertEquals(listOf(0L, 8_000L), openedAt)

            backend.play()
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()

            assertTrue(
                "playback continues from the new place: ${backend.state.value.positionMs}",
                backend.state.value.positionMs in 8_500..9_500,
            )
        }

    /** A file served as it is is moved inside what is open, with no second request. */
    @Test
    fun `a stream that can be sought is sought in place`() =
        runTest {
            seekable = true
            val backend = backend(tracks(20))
            backend.play()
            runCurrent()
            advanceTimeBy(2001)
            runCurrent()

            backend.seekTo(10_000)
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()

            assertEquals(listOf(0L), openedAt)
            assertTrue(opened.single().told.contains("seekTo(10000)"))
            assertTrue(backend.state.value.positionMs in 10_500..11_500)
        }

    /**
     * What the real decoder is told to seek to on opening: 0 for a stream that cannot be
     * sought, which already starts at the place.
     */
    @Test
    fun `a request that cannot be sought starts its decoder at the top of what the server sends`() {
        assertEquals(0L, StreamRequest("https://music.example/a", seekable = false).decoderStartMs(42_000))
        assertEquals(42_000L, StreamRequest("https://music.example/a").decoderStartMs(42_000))
    }

    private fun tracks(vararg durationsSec: Int): List<Track> =
        durationsSec.mapIndexed { at, sec -> Track("t$at", "Artist $at", "Title $at", sec * 1000L, uri = "stream:track:t$at") }

    private fun TestScope.backend(tracks: List<Track>): StreamBackend =
        StreamBackend(
            tracks = tracks,
            scope = backgroundScope,
            open = { track, positionMs -> decoder(track, positionMs) },
            out = FakeAudioOut(backgroundScope),
        )

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
                label = row.id,
                seekable = seekable,
            )
        opened += made
        openedAt += positionMs
        return made
    }
}
