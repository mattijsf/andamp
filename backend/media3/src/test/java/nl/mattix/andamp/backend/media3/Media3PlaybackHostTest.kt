// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The player, the backend and the session are one per process.
 *
 * Asking twice gives the same player, so a widget press and a window reach
 * one player. A backend the host did not build is hosted, and the session
 * goes to the player hosted last. [Media3PlaybackHost.reset] keeps these cases
 * from inheriting each other's state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Media3PlaybackHostTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val asked = mutableListOf<Boolean>()
    private lateinit var real: (Context, Boolean) -> Unit

    /** A session service built for the test, destroyed after it. */
    private var running: ServiceController<PlaybackService>? = null

    private val tracks =
        listOf(
            Track("t1", "Neon Cassette", "Midnight Drive", 254_000, uri = "asset:///audio/llama.mp3"),
        )

    @Before
    fun watchTheStart() {
        real = Media3PlaybackHost.start
        Media3PlaybackHost.start = { _, foreground -> asked += foreground }
        Media3PlaybackHost.stopService(app) // clears serviceStarted
    }

    @After
    fun tearDown() {
        running?.destroy()
        Media3PlaybackHost.reset(app)
        Media3PlaybackHost.start = real
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `asking twice gives the same player`() {
        val first = Media3PlaybackHost.backend(app, tracks)
        shadowOf(Looper.getMainLooper()).idle()

        val second = Media3PlaybackHost.backend(app, tracks)

        assertSame(first, second)
    }

    @Test
    fun `after a reset the next ask builds a fresh player`() {
        val first = Media3PlaybackHost.backend(app, tracks)
        shadowOf(Looper.getMainLooper()).idle()

        Media3PlaybackHost.reset(app)
        shadowOf(Looper.getMainLooper()).idle()
        val second = Media3PlaybackHost.backend(app, tracks)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotSame(first, second)
    }

    /**
     * The phone's own player is built first, and the hosted one takes the
     * session from it: a session over the phone's player knows only the
     * phone's rows.
     */
    @Test
    fun `a hosted backend is the one the session plays`() {
        val phone = Media3PlaybackHost.backend(app, tracks)
        Media3PlaybackHost.host(app, MockBackend(tracks, scope))
        shadowOf(Looper.getMainLooper()).idle()

        val session = Media3PlaybackHost.session(app)

        assertTrue("the session is over the hosted backend", session?.player is BackendPlayer)
        assertNotSame(phone.playerForSession, session?.player)
    }

    /** A car can be the first to ask: it starts the process with the service and no window. */
    @Test
    fun `a session asked for before there is a player has the app build one`() {
        val before = Media3PlaybackHost.wake
        Media3PlaybackHost.wake = { context -> Media3PlaybackHost.host(context, MockBackend(tracks, scope)) }

        val session = Media3PlaybackHost.session(app)
        Media3PlaybackHost.wake = before

        assertEquals(1, session?.player?.mediaItemCount)
    }

    @Test
    fun `with nobody to build a player there is no session`() {
        assertNull(Media3PlaybackHost.session(app))
    }

    /** The foreground service keeps a backend that renders its own audio alive with the screen off. */
    @Test
    fun `playing through a hosted backend starts the service in front`() {
        Media3PlaybackHost.backend(app, tracks)
        val hosted = MockBackend(tracks, scope)
        Media3PlaybackHost.host(app, hosted)
        shadowOf(Looper.getMainLooper()).idle()

        hosted.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(true), asked)
    }

    /**
     * The floating player keeps the service running in the background, and from Android 15 a
     * play from there is refused the audio focus unless the service is in the foreground by
     * then.
     */
    @Test
    fun `a play while the service runs in the background puts it in the foreground first`() {
        val heard = Heard(MockBackend(tracks, scope))
        val driven = Media3PlaybackHost.host(app, heard)
        val service = startService()
        assertEquals(
            "the service starts in the background",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
            service.foregroundServiceType,
        )

        driven.play()

        assertEquals(
            "the service was in the foreground when the backend began to play",
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK),
            heard.inFrontAtPlay,
        )
    }

    /** Before Android 15 the audio focus has no such rule, and the service is left as it is. */
    @Test
    @Config(sdk = [34])
    fun `before Android 15 a play leaves a background service as it is`() {
        val heard = Heard(MockBackend(tracks, scope))
        val driven = Media3PlaybackHost.host(app, heard)
        startService()

        driven.play()

        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE), heard.inFrontAtPlay)
    }

    /** With no service running, a play goes to the backend, and the service follows the music. */
    @Test
    fun `with no service running a play goes straight to the backend`() {
        val hosted = MockBackend(tracks, scope)
        val driven = Media3PlaybackHost.host(app, hosted)
        shadowOf(Looper.getMainLooper()).idle()

        driven.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Transport.Playing, hosted.state.value.transport)
        assertEquals("started in the foreground once playing", listOf(true), asked)
    }

    /** Creates the session service, as the floating player's background start does, and lets Media3 settle. */
    private fun startService(): PlaybackService {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        running = controller
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }

    /** The backend, noting whether the service was in the foreground each time a play reached it. */
    private inner class Heard(
        private val backend: PlaybackBackend,
    ) : PlaybackBackend by backend {
        val inFrontAtPlay = mutableListOf<Int>()

        override fun play() {
            inFrontAtPlay += running?.get()?.foregroundServiceType ?: -1
            backend.play()
        }
    }
}
