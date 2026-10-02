// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which way the session service is started, which decides whether playback
 * survives the app going away.
 *
 * A plain startService gives a background service, which the system stops
 * after the app leaves the foreground (API 26 and later). A foreground service
 * is left alone, and Media3 calls startForeground once a playing player gives
 * it a notification to post.
 *
 * The idle path cannot ask for a foreground start: the floating player calls
 * keepAlive with nothing playing, and a foreground start that reaches no
 * startForeground within five seconds takes the process down.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceStartTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<Boolean>()
    private lateinit var real: (Context, Boolean) -> Unit

    @Before
    fun watchTheStart() {
        real = Media3PlaybackHost.start
        Media3PlaybackHost.start = { _, foreground -> asked += foreground }
        Media3PlaybackHost.stopService(app) // clears serviceStarted
        asked.clear()
    }

    @After
    fun putItBack() {
        Media3PlaybackHost.start = real
        Media3PlaybackHost.stopService(app)
    }

    @Test
    fun `keeping the process warm for the floating player is a background start`() {
        Media3PlaybackHost.ensureService(app)

        assertEquals("keeping the process warm asks for a background start", listOf(false), asked)
    }

    @Test
    fun `the service is only started once`() {
        Media3PlaybackHost.ensureService(app)
        Media3PlaybackHost.ensureService(app)

        assertEquals(listOf(false), asked)
    }
}
