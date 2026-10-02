// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * What the transport remembers between sessions: shuffle and repeat. Volume is not stored; it
 * is the phone's own media volume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransportMemoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clean() {
        app
            .getSharedPreferences("transport", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun store() = TransportStore(app)

    private fun vm(): WinampViewModel =
        WinampViewModel(app, { MockBackend(FakeTracks.tracks, it) }).also { ShadowLooper.idleMainLooper() }

    @Test
    fun `nothing remembered is everything at its default`() {
        val stored = store().load()

        assertEquals(false, stored.shuffle)
        assertEquals(false, stored.repeat)
    }

    @Test
    fun `moving a flag is remembered`() {
        val player = vm()

        player.toggleShuffle()
        player.toggleRepeat()
        ShadowLooper.idleMainLooper()

        val stored = store().load()
        assertTrue("shuffle", stored.shuffle)
        assertTrue("repeat", stored.repeat)
    }

    @Test
    fun `what was remembered is what the next session starts with`() {
        store().saveSettings(TransportState(shuffle = true, repeat = true))

        val player = vm()
        ShadowLooper.idleMainLooper()

        assertTrue("shuffle", player.state.shuffle)
        assertTrue("repeat", player.state.repeat)
    }

    @Test
    fun `coming back does not start playing`() {
        store().saveSettings(TransportState(shuffle = true))

        val player = vm()
        ShadowLooper.idleMainLooper()

        // not playing: a phone in a pocket must not start because an app
        // was opened
        assertEquals(nl.mattix.andamp.core.model.Transport.Stopped, player.state.transport)
    }
}
