// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The player, the backend and the session are one per process.
 *
 * Asking twice gives the same player, so a widget press and a window reach
 * one player. A backend the host did not build is hosted, and the session
 * goes to the player hosted last. [Media3PlaybackHost.reset] keeps these cases
 * from inheriting each other's state.
 */
@RunWith(RobolectricTestRunner::class)
class Media3PlaybackHostTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val asked = mutableListOf<Boolean>()
    private lateinit var real: (Context, Boolean) -> Unit

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
}
