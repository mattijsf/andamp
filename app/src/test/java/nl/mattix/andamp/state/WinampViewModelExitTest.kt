// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Winamp's Exit, and the floating player outliving its activity. Both are asked of the backend,
 * so a backend with no service of the app's to stop takes part too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WinampViewModelExitTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A real backend for everything but the two lifecycle verbs, which it records. */
    private class Recording(
        inner: MockBackend,
    ) : nl.mattix.andamp.core.playback.PlaybackBackend by inner {
        var toreDown = false
        var keptAlive = false

        override fun teardown() {
            toreDown = true
        }

        override fun keepAlive() {
            keptAlive = true
        }
    }

    private fun player(backend: (CoroutineScope) -> nl.mattix.andamp.core.playback.PlaybackBackend) =
        WinampViewModel(app, createBackend = backend, presetStore = InMemoryEqPresetStore())

    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!until() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `exit stops the music and lets the backend put itself away`() {
        lateinit var backend: Recording
        val vm = player { scope -> Recording(MockBackend(FakeTracks.tracks, scope)).also { backend = it } }

        vm.play()
        vm.exit()
        settle { vm.state.transport == Transport.Stopped }

        assertEquals(Transport.Stopped, vm.state.transport)
        assertTrue("the backend is told to put itself away", backend.toreDown)
    }

    @Test
    fun `the floating player asks the backend to outlive the activity`() {
        lateinit var backend: Recording
        val vm = player { scope -> Recording(MockBackend(FakeTracks.tracks, scope)).also { backend = it } }

        vm.keepPlayingWithoutAWindow()

        assertTrue(backend.keptAlive)
    }

    /** A backend that needs neither verb is not a special case at the call site. */
    @Test
    fun `a backend that overrides nothing survives both verbs`() {
        val vm = player { scope -> MockBackend(FakeTracks.tracks, scope) }

        vm.keepPlayingWithoutAWindow()
        vm.exit()
        settle { vm.state.transport == Transport.Stopped }

        assertEquals(Transport.Stopped, vm.state.transport)
        assertFalse("no service of the app's is stopped", shadowOf(app).nextStoppedService != null)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
