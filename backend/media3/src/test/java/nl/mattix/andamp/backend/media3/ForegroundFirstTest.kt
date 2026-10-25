// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.PlaybackBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which presses ask for the foreground before they reach the backend: the ones that start the
 * music, and those only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundFirstTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val heard = mutableListOf<String>()
    private val backend = MockBackend(tracks, scope)
    private val driven = ForegroundFirst(Told(backend, heard)) { heard += "forward" }

    @After
    fun tearDown() {
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `play and a row played come forward before the backend hears them`() {
        driven.play()
        driven.playAt(1)

        assertEquals(listOf("forward", "play", "forward", "playAt"), heard)
    }

    @Test
    fun `pause, next and previous come forward first when they resume`() {
        backend.play()
        backend.pause()

        driven.pause() // resumes
        backend.pause()
        driven.next()
        backend.pause()
        driven.previous()

        assertEquals(listOf("forward", "pause", "forward", "next", "forward", "previous"), heard)
    }

    @Test
    fun `pause, next and previous go straight through while playing or stopped`() {
        driven.next() // stopped: moves the cursor
        driven.previous()
        backend.play()
        driven.next()
        driven.previous()
        driven.pause()

        assertEquals(Transport.Paused, backend.state.value.transport)
        assertEquals(listOf("next", "previous", "next", "previous", "pause"), heard)
    }

    /** The backend, with each press that reaches it written down. */
    private class Told(
        private val backend: PlaybackBackend,
        private val heard: MutableList<String>,
    ) : PlaybackBackend by backend {
        override fun play() {
            heard += "play"
            backend.play()
        }

        override fun playAt(index: Int) {
            heard += "playAt"
            backend.playAt(index)
        }

        override fun pause() {
            heard += "pause"
            backend.pause()
        }

        override fun next() {
            heard += "next"
            backend.next()
        }

        override fun previous() {
            heard += "previous"
            backend.previous()
        }
    }

    private companion object {
        val tracks =
            listOf(
                Track("t1", "Neon Cassette", "Midnight Drive", 254_000),
                Track("t2", "Neon Cassette", "Chrome Hearts", 198_000),
                Track("t3", "Neon Cassette", "Afterglow", 221_000),
            )
    }
}
