// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavioral contract every [PlaybackBackend] should pass: subclass per backend and
 * implement [createBackend]. Time-dependent assertions run on the TestScope's virtual
 * clock; a backend whose position source cannot be virtualized overrides the tick-based
 * tests and does not skip the suite.
 */
abstract class PlaybackBackendContractTest {
    protected abstract fun TestScope.createBackend(tracks: List<Track>): PlaybackBackend

    protected fun tracks(vararg durationsSec: Int): List<Track> =
        durationsSec.mapIndexed { i, sec -> Track("t$i", "Artist $i", "Title $i", sec * 1000L) }

    @Test
    fun `starts stopped at the queue head with position zero`() =
        runTest {
            val backend = createBackend(tracks(10, 20))
            val s = backend.state.value
            assertEquals(Transport.Stopped, s.transport)
            assertEquals(0L, s.positionMs)
            assertEquals(0, s.currentIndex)
            assertEquals(2, s.queue.size)
        }

    @Test
    fun `play starts playback and play again restarts the track`() =
        runTest {
            val backend = createBackend(tracks(10, 20))
            backend.play()
            assertEquals(Transport.Playing, backend.state.value.transport)
            advanceTimeBy(3001)
            runCurrent()
            assertTrue(backend.state.value.positionMs >= 3000)
            backend.play()
            assertEquals(0L, backend.state.value.positionMs)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `pause toggles and does nothing while stopped`() =
        runTest {
            val backend = createBackend(tracks(10))
            backend.pause()
            assertEquals(Transport.Stopped, backend.state.value.transport)
            backend.play()
            backend.pause()
            assertEquals(Transport.Paused, backend.state.value.transport)
            backend.pause()
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `position freezes while paused`() =
        runTest {
            val backend = createBackend(tracks(10))
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            val posAtPause = backend.state.value.positionMs
            backend.pause()
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(posAtPause, backend.state.value.positionMs)
        }

    @Test
    fun `stop resets the position`() =
        runTest {
            val backend = createBackend(tracks(10))
            backend.play()
            advanceTimeBy(3001)
            runCurrent()
            backend.stop()
            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(0L, backend.state.value.positionMs)
        }

    @Test
    fun `next and previous wrap around the queue`() =
        runTest {
            val backend = createBackend(tracks(10, 20, 30))
            backend.next()
            assertEquals(1, backend.state.value.currentIndex)
            backend.next()
            backend.next()
            assertEquals(0, backend.state.value.currentIndex)
            backend.previous()
            assertEquals(2, backend.state.value.currentIndex)
        }

    @Test
    fun `playAt jumps to the track and starts playing from zero`() =
        runTest {
            val backend = createBackend(tracks(10, 20, 30))
            backend.playAt(2)
            val s = backend.state.value
            assertEquals(2, s.currentIndex)
            assertEquals(Transport.Playing, s.transport)
            assertEquals(0L, s.positionMs)
        }

    @Test
    fun `playAt on an empty queue leaves it stopped`() =
        runTest {
            val backend = createBackend(emptyList())

            backend.playAt(0)
            runCurrent()

            assertEquals("an empty queue stays stopped", Transport.Stopped, backend.state.value.transport)
            assertTrue(
                backend.state.value.queue
                    .isEmpty(),
            )
        }

    @Test
    fun `a seek while stopped is where play starts`() =
        runTest {
            // how the player restores a position after a relaunch: it seeks before anything plays
            val backend = createBackend(tracks(60, 20))
            backend.seekTo(30_000)
            runCurrent()

            backend.play()
            runCurrent()

            assertEquals(Transport.Playing, backend.state.value.transport)
            assertEquals(30_000L, backend.state.value.positionMs)
            advanceTimeBy(1001)
            runCurrent()
            assertTrue(
                "the position advances from the seek point: ${backend.state.value.positionMs}",
                backend.state.value.positionMs >= 31_000,
            )
        }

    @Test
    fun `seek clamps to the track duration`() =
        runTest {
            val backend = createBackend(tracks(10))
            backend.play()
            backend.seekTo(99_000)
            assertEquals(10_000L, backend.state.value.positionMs)
            backend.seekTo(-5)
            assertEquals(0L, backend.state.value.positionMs)
        }

    @Test
    fun `position advances on the clock while playing`() =
        runTest {
            val backend = createBackend(tracks(10))
            backend.play()
            advanceTimeBy(3001)
            runCurrent()
            assertEquals(3000L, backend.state.value.positionMs)
        }

    @Test
    fun `track end advances to the next track`() =
        runTest {
            val backend = createBackend(tracks(2, 20))
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            val s = backend.state.value
            assertEquals(1, s.currentIndex)
            assertEquals(Transport.Playing, s.transport)
        }

    @Test
    fun `queue end without repeat stops playback`() =
        runTest {
            val backend = createBackend(tracks(2))
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(0L, backend.state.value.positionMs)
        }

    @Test
    fun `the end of the queue stops at its head`() =
        runTest {
            val backend = createBackend(tracks(10, 2))
            backend.playAt(1)
            runCurrent()

            advanceTimeBy(2001)
            runCurrent()

            val s = backend.state.value
            assertEquals(Transport.Stopped, s.transport)
            assertEquals("the cursor returns to the head of the queue", 0, s.currentIndex)
            assertEquals(0L, s.positionMs)
        }

    @Test
    fun `stop after current stops at the end of the track it was set on`() =
        runTest {
            val backend = createBackend(tracks(20, 2, 20))
            backend.playAt(1)
            runCurrent()

            backend.setStopAfterCurrent(true)
            advanceTimeBy(2001)
            runCurrent()

            val s = backend.state.value
            assertEquals(Transport.Stopped, s.transport)
            assertEquals("the cursor stays on the track that finished", 1, s.currentIndex)
            assertEquals(0L, s.positionMs)
        }

    @Test
    fun `queue end with repeat wraps to the head`() =
        runTest {
            val backend = createBackend(tracks(2, 2))
            backend.setRepeat(true)
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            assertEquals(1, backend.state.value.currentIndex)
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(0, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `shuffle advance stays within the queue and keeps playing`() =
        runTest {
            val backend = createBackend(tracks(2, 20, 20, 20))
            backend.setShuffle(true)
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            val s = backend.state.value
            assertTrue(s.currentIndex in s.queue.indices)
            assertEquals(Transport.Playing, s.transport)
        }

    @Test
    fun `setQueue keeps playback running when the current track survives the edit`() =
        runTest {
            val backend = createBackend(tracks(10, 20, 30))
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            val queue = backend.state.value.queue
            backend.setQueue(listOf(queue[0], queue[2])) // current (index 0) survives
            val s = backend.state.value
            assertEquals(Transport.Playing, s.transport)
            assertEquals(0, s.currentIndex)
            assertEquals(2000L, s.positionMs)
            assertEquals(2, s.queue.size)
        }

    @Test
    fun `setQueue stops when the current track is removed`() =
        runTest {
            val backend = createBackend(tracks(10, 20))
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            backend.setQueue(listOf(backend.state.value.queue[1]))
            val s = backend.state.value
            assertEquals(Transport.Stopped, s.transport)
            assertEquals(0L, s.positionMs)
        }

    @Test
    fun `setQueue with an empty list stops at index zero`() =
        runTest {
            val backend = createBackend(tracks(10))
            backend.play()
            backend.setQueue(emptyList())
            val s = backend.state.value
            assertEquals(Transport.Stopped, s.transport)
            assertEquals(0, s.currentIndex)
            assertTrue(s.queue.isEmpty())
        }

    @Test
    fun `enqueue appends without interrupting playback`() =
        runTest {
            val backend = createBackend(tracks(10, 20))
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            backend.enqueue(listOf(Track("e0", "Artist e", "Extra", 5_000)))
            val s = backend.state.value
            assertEquals(Transport.Playing, s.transport)
            assertEquals(0, s.currentIndex)
            assertEquals(2000L, s.positionMs)
            assertEquals(listOf("t0", "t1", "e0"), s.queue.map { it.id })
        }

    @Test
    fun `changing whose volume the slider moves disturbs nothing else`() =
        runTest {
            // The slider's position is not asserted: where there are two volumes they
            // multiply, and a backend may move the slider to keep the loudness. Playback
            // must not change.
            val backend = createBackend(tracks(10, 20))
            backend.play()
            advanceTimeBy(2001)
            runCurrent()
            backend.setVolume(0.4f)

            backend.setVolumeMode(VolumeMode.APP)
            backend.setVolumeMode(VolumeMode.DEVICE)

            val s = backend.state.value
            assertEquals(Transport.Playing, s.transport)
            assertEquals(0, s.currentIndex)
            assertEquals(2000L, s.positionMs)
            assertEquals(listOf("t0", "t1"), s.queue.map { it.id })
        }

    @Test
    fun `next from paused plays what it lands on`() =
        runTest {
            val backend = createBackend(tracks(10, 20, 30))
            backend.play()
            runCurrent()
            backend.pause()
            runCurrent()

            backend.next()
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(
                "next from paused starts playback",
                Transport.Playing,
                backend.state.value.transport,
            )
        }

    @Test
    fun `next from stopped only moves the cursor`() =
        runTest {
            val backend = createBackend(tracks(10, 20, 30))

            backend.next()
            runCurrent()

            val s = backend.state.value
            assertEquals("next moves the cursor to the second track", 1, s.currentIndex)
            assertEquals("next from stopped stays stopped", Transport.Stopped, s.transport)
            assertEquals(0L, s.positionMs)
        }

    @Test
    fun `previous from stopped wraps to the end without playing`() =
        runTest {
            val backend = createBackend(tracks(10, 20, 30))

            backend.previous()
            runCurrent()

            assertEquals(2, backend.state.value.currentIndex)
            assertEquals(Transport.Stopped, backend.state.value.transport)
        }

    @Test
    fun `stopping a station puts its name back`() =
        runTest {
            // ICY metadata renames the row to the song while it plays; stopping puts the
            // station's own name back
            val station =
                Track(
                    "s0",
                    "",
                    "Radio Example",
                    0,
                    uri = "https://radio.example.org/stream.mp3",
                    defaultName = "Radio Example",
                    isStream = true,
                )
            val backend = createBackend(listOf(station) + tracks(10))
            backend.play()
            runCurrent()
            backend.patchTracks(listOf(station.copy(artist = "Muse", title = "Hexagons")))
            assertEquals(
                "Hexagons",
                backend.state.value.queue[0]
                    .title,
            )

            backend.stop()
            runCurrent()

            assertEquals(
                "Radio Example",
                backend.state.value.queue[0]
                    .title,
            )
            assertEquals(
                "",
                backend.state.value.queue[0]
                    .artist,
            )
        }

    @Test
    fun `moving on from a station puts its name back`() =
        runTest {
            // moving on puts the station's own name back, as a stop does
            val station =
                Track(
                    "s0",
                    "",
                    "Radio Example",
                    0,
                    uri = "https://radio.example.org/stream.mp3",
                    defaultName = "Radio Example",
                    isStream = true,
                )
            val backend = createBackend(listOf(station) + tracks(10))
            backend.play()
            runCurrent()
            backend.patchTracks(listOf(station.copy(artist = "Muse", title = "Hexagons")))

            backend.next()
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(
                "Radio Example",
                backend.state.value.queue[0]
                    .title,
            )
            assertEquals(
                "",
                backend.state.value.queue[0]
                    .artist,
            )
        }

    @Test
    fun `patching a track rewrites its metadata and nothing else`() =
        runTest {
            val backend = createBackend(tracks(10, 20))
            backend.play()
            advanceTimeBy(2001)
            runCurrent()

            backend.patchTracks(listOf(Track("t1", "Muse", "Hexagons", 326_000)))

            val s = backend.state.value
            assertEquals("the patch sets the title", "Hexagons", s.queue[1].title)
            assertEquals("Muse", s.queue[1].artist)
            assertEquals("the patch leaves playback running", Transport.Playing, s.transport)
            assertEquals(0, s.currentIndex)
            assertEquals(2000L, s.positionMs)
            assertEquals(listOf("t0", "t1"), s.queue.map { it.id })
        }

    @Test
    fun `a patch does not change an entry's uri`() =
        runTest {
            val backend = createBackend(tracks(10, 20))
            val before =
                backend.state.value.queue[0]
                    .uri

            backend.patchTracks(listOf(Track("t0", "Muse", "Cryogen", 301_000, uri = "content://elsewhere")))

            assertEquals(
                "the patch keeps the entry's uri",
                before,
                backend.state.value.queue[0]
                    .uri,
            )
            assertEquals(
                "Cryogen",
                backend.state.value.queue[0]
                    .title,
            )
        }

    @Test
    fun `patching an id the queue does not hold changes nothing`() =
        runTest {
            val backend = createBackend(tracks(10, 20))
            val before = backend.state.value.queue

            backend.patchTracks(listOf(Track("nobody", "X", "Y", 1)))

            assertEquals(before, backend.state.value.queue)
        }

    @Test
    fun `enqueue while stopped leaves the cursor where it was`() =
        runTest {
            val backend = createBackend(tracks(10, 20, 30))
            backend.next() // cursor parked mid-queue, still stopped
            backend.enqueue(listOf(Track("e0", "Artist e", "Extra", 5_000)))
            val s = backend.state.value
            assertEquals(Transport.Stopped, s.transport)
            assertEquals(1, s.currentIndex)
            assertEquals(0L, s.positionMs)
            assertEquals(4, s.queue.size)
        }

    @Test
    fun `volume clamps to the unit range`() =
        runTest {
            val backend = createBackend(tracks(10))
            backend.setVolume(1.5f)
            assertEquals(1f, backend.state.value.volumeFraction)
            backend.setVolume(-0.5f)
            assertEquals(0f, backend.state.value.volumeFraction)
        }
}
