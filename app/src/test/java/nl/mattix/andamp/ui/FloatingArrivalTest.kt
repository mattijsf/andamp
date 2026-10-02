// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.window.BIG_WINDOW
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Arriving somewhere while the player is set to float.
 *
 * With Always On Top on, the player lives in the floating window and the app's own player
 * screen hands over to it. A visit from outside, such as the widget's Preferences, lands on
 * that same screen for one frame before it moves on; the handover must not happen in that
 * frame, or the app closes before the destination is shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class FloatingArrivalTest {
    @get:Rule
    val compose = createComposeRule()

    /** A view model whose floating player is switched on and allowed. */
    private fun floating(): WinampViewModel {
        val vm = testViewModel()
        ShadowSettings.setCanDrawOverlays(true)
        vm.overlayOps.want(true)
        return vm
    }

    /**
     * The clock is driven by hand: the player draws a visualizer, which is an
     * animation that never finishes, so waiting for the screen to go idle never
     * returns.
     */
    private fun show(vm: WinampViewModel): List<Boolean> {
        val handed = mutableListOf<Boolean>()
        compose.mainClock.autoAdvance = false
        compose.setContent { WinampScreen(vm, onFloatingChanged = { handed += it }) }
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        return handed
    }

    @Test
    fun `a screen asked for from outside is shown, not handed back to the floating player`() {
        val vm = floating()
        vm.state.arrivalDestination = Screen.PREFERENCES

        val handed = show(vm)

        assertEquals("the screen stays in the app", emptyList<Boolean>(), handed)
        compose.onNodeWithTag("prefs.open.dsp").assertExists()
    }

    @Test
    fun `the player itself still goes to the floating window`() {
        val handed = show(floating())

        // asked for more than once over these frames, which the activity takes
        // as the one handover it already made
        assertEquals("the player hands over to the floating window", listOf(true), handed.distinct())
    }

    private companion object {
        const val FRAMES = 8
    }
}
