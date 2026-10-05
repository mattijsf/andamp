// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A player that fills the screen has black under the navigation bar, so the scrim the system
 * draws behind the bar's buttons is switched off for as long as that lasts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationBarOverBlackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var onPlayer by mutableStateOf(true)
    private var fills by mutableStateOf(false)

    private fun scrim(): Boolean = compose.activity.window.isNavigationBarContrastEnforced

    private fun show() {
        compose.activity.window.isNavigationBarContrastEnforced = true
        compose.setContent { StatusBarIcons(onPlayer = onPlayer, playerFillsScreen = fills, dark = false) }
        compose.waitForIdle()
    }

    @Test
    fun `the floating player leaves the bar as it is`() {
        show()

        assertTrue(scrim())
    }

    @Test
    fun `filling the screen takes the scrim away, and switching it off gives it back`() {
        show()

        fills = true
        compose.waitForIdle()
        assertFalse(scrim())

        fills = false
        compose.waitForIdle()
        assertTrue(scrim())
    }

    @Test
    fun `another screen over a filled player gets the bar back`() {
        fills = true
        show()
        assertFalse(scrim())

        onPlayer = false
        compose.waitForIdle()

        assertTrue(scrim())
    }
}
