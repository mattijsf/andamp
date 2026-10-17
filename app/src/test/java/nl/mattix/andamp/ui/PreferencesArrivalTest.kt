// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.prefs.PLUGINS_PAGE
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A page of Preferences asked for from outside, such as the plug-ins page a plug-in link on
 * the site opens, lands on that page whatever was on screen when it was asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferencesArrivalTest {
    @get:Rule
    val compose = createComposeRule()

    /** The clock is driven by hand: the player draws a visualizer, which never goes idle. */
    private fun show(vm: WinampViewModel) {
        compose.mainClock.autoAdvance = false
        compose.setContent { WinampScreen(vm) }
        frames()
    }

    private fun frames() = repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }

    /** What PluginImportActivity asks for when a plug-in link arrives. */
    private fun pluginLink(vm: WinampViewModel) {
        compose.runOnUiThread {
            vm.state.arrivalPage = PLUGINS_PAGE
            vm.state.arrivalDestination = Screen.PREFERENCES
            Snapshot.sendApplyNotifications()
        }
        frames()
    }

    @Test
    fun `a plug-in link from the player lands on the plug-ins page`() {
        val vm = testViewModel()
        show(vm)

        pluginLink(vm)

        compose.onNodeWithTag("prefs.plugins.add").assertExists()
    }

    @Test
    fun `a plug-in link while Preferences shows its list lands on the plug-ins page`() {
        val vm = testViewModel()
        vm.state.arrivalDestination = Screen.PREFERENCES
        show(vm)

        pluginLink(vm)

        compose.onNodeWithTag("prefs.plugins.add").assertExists()
        compose.onNodeWithTag("prefs.open.dsp").assertDoesNotExist()
    }

    @Test
    fun `a plug-in link while another page of Preferences is open lands on the plug-ins page`() {
        val vm = testViewModel()
        vm.state.arrivalDestination = Screen.PREFERENCES
        show(vm)
        compose.onNodeWithTag("prefs.open.dsp").performClick()
        frames()

        pluginLink(vm)

        compose.onNodeWithTag("prefs.plugins.add").assertExists()
        compose.onNodeWithTag("prefs.open.dsp").assertDoesNotExist()
    }

    private companion object {
        // long enough for a screen's slide-in to finish, so only what stays on screen is there
        const val FRAMES = 90
    }
}
