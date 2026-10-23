// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.PluginOps
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.prefs.PreferencesPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the back gesture closes: a preferences page, the skin manager, the visualizer's full
 * screen and a modern skin's window each take it before the player does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class BackGestureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun player(): WinampState {
        val state = WinampState()
        compose.setContent { BackCloses(state) }
        return state
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `back walks a preferences page back, and leaves the screen to its host`() {
        // the preferences walk their own page stack; the screen itself is a
        // destination of the host above it, and the host pops it
        var open = true
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)))
        val dsp = DspOps(app, facade)
        compose.setContent {
            if (open) {
                PreferencesPage(
                    access = LibraryAccess.GRANTED,
                    dsp = dsp,
                    plugins = PluginOps(app, facade, dsp, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)),
                    onRequestAccess = {},
                    onPickPlugin = {},
                    onClose = { open = false },
                )
            }
        }

        compose.onNodeWithTag("prefs.open.dsp").performClick()
        compose.waitForIdle()

        back()
        assertTrue("back closes the page and keeps the preferences open", open)
        compose.onNodeWithTag("prefs.open.dsp").assertIsDisplayed()

        back()
        assertTrue("the preferences leave closing the screen to the host", open)
    }

    @Test
    fun `back walks a page back to the one it was opened from`() {
        // Plug-ins is opened from Effects, so back from it returns to Effects
        var open = true
        // the door to the plug-ins only appears on a backend that runs effects
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val facade = PlayerFacade(WithDsp(scope))
        val dsp = DspOps(app, facade)
        compose.setContent {
            if (open) {
                PreferencesPage(
                    access = LibraryAccess.GRANTED,
                    dsp = dsp,
                    plugins = PluginOps(app, facade, dsp, scope),
                    onRequestAccess = {},
                    onPickPlugin = {},
                    onClose = { open = false },
                )
            }
        }

        compose.onNodeWithTag("prefs.open.dsp").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("prefs.plugins.manage").performClick()
        compose.waitForIdle()

        back()

        compose.onNodeWithTag("prefs.plugins.manage").assertIsDisplayed()
        assertTrue("back steps to the previous page and keeps the preferences open", open)
    }

    @Test
    fun `back closes the skin manager rather than the player`() {
        val vm = player()
        compose.runOnUiThread { vm.skinManagerOpen = true }
        compose.waitForIdle()

        back()

        assertFalse("back closes the skin manager", vm.skinManagerOpen)
        assertTrue("the player activity stays open", compose.activity.isFinishing.not())
    }

    @Test
    fun `back leaves the visualizer's full screen before it leaves anything else`() {
        val vm = player()
        compose.runOnUiThread { vm.milkdropFullscreen = true }
        compose.waitForIdle()

        back()

        assertFalse(vm.milkdropFullscreen)
    }

    @Test
    fun `the innermost thing goes first`() {
        val vm = player()
        compose.runOnUiThread {
            vm.milkdropFullscreen = true
            vm.skinManagerOpen = true
        }
        compose.waitForIdle()

        back()

        assertFalse("back closes the skin manager first", vm.skinManagerOpen)
        assertTrue("the visualizer stays full screen", vm.milkdropFullscreen)
    }

    @Test
    fun `a modern skin's window closes after the skin manager, the library and the visualizer's full screen`() {
        val state = WinampState()
        var closed = 0
        compose.setContent { BackCloses(state, skinWindowOpen = true, closeSkinWindow = { closed++ }) }
        compose.runOnUiThread {
            state.skinManagerOpen = true
            state.libraryOpen = true
            state.milkdropFullscreen = true
        }
        compose.waitForIdle()

        back()
        assertFalse("the skin manager goes first", state.skinManagerOpen)
        back()
        assertFalse("then the library", state.libraryOpen)
        back()
        assertFalse("then the visualizer's full screen", state.milkdropFullscreen)
        assertEquals("the skin's window waits for all of them", 0, closed)

        back()
        assertEquals("then the skin's window, once", 1, closed)
        assertTrue("the player activity stays open", compose.activity.isFinishing.not())
    }

    @Test
    fun `with no skin window open, back never reaches the skin`() {
        val state = WinampState()
        var closed = 0
        compose.setContent { BackCloses(state, skinWindowOpen = false, closeSkinWindow = { closed++ }) }
        compose.runOnUiThread { state.milkdropFullscreen = true }
        compose.waitForIdle()

        back()
        assertFalse(state.milkdropFullscreen)
        assertFalse("nothing is left to take back", compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())

        back()
        assertEquals("the skin never hears it", 0, closed)
        assertTrue("the last back leaves the player", compose.activity.isFinishing)
    }

    /** A backend that runs effects, so the Effects page has its plug-ins door. */
    private class WithDsp(
        scope: CoroutineScope,
    ) : PlaybackBackend by MockBackend(FakeTracks.tracks, scope) {
        private val delegate = MockBackend(FakeTracks.tracks, scope)

        override val state get() = delegate.state

        override val capabilities: Capabilities get() = delegate.capabilities.copy(hasDsp = true)
    }
}
