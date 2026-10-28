// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player asked for from outside, as a skin handed over from another app and the offer taken
 * on the widget's menu ask for it, closes the screens over the player, so Back does not bring
 * them back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerArrivalTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun frames() = repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }

    @Test
    fun `the player asked for while a page of Preferences is open closes Preferences`() {
        val vm = testViewModel()
        vm.state.arrivalDestination = Screen.PREFERENCES
        // the clock is driven by hand: the player draws a visualizer, which never goes idle
        compose.mainClock.autoAdvance = false
        compose.setContent { WinampScreen(vm) }
        frames()
        compose.onNodeWithTag("prefs.open.dsp").performClick()
        frames()

        compose.runOnUiThread {
            vm.state.arrivalDestination = Screen.PLAYER
            Snapshot.sendApplyNotifications()
        }
        frames()

        assertFalse("nothing covers the player", vm.state.playerCovered)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames()
        assertFalse("Back does not bring Preferences back", vm.state.playerCovered)
        compose.onNodeWithTag("prefs.open.dsp").assertDoesNotExist()
    }

    private companion object {
        // long enough for a screen's slide-in to finish, so only what stays on screen is there
        const val FRAMES = 90
    }
}
