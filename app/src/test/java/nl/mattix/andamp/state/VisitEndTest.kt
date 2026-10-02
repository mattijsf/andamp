// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.ui.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Done goes when a screen was opened from outside the app, such as from the home screen
 * widget. Leaving the screen that was asked for ends the visit; any other step back is ordinary
 * navigation inside the app.
 */
class VisitEndTest {
    @Test
    fun `leaving the screen that was asked for ends the visit`() {
        val state = WinampState()
        state.arrivedAt = Screen.PREFERENCES

        assertTrue(state.leavingEndsTheVisit(Screen.PREFERENCES))
    }

    @Test
    fun `leaving somewhere else is an ordinary step back`() {
        // the museum was reached from Preferences; Done there steps back inside the app
        val state = WinampState()
        state.arrivedAt = Screen.PREFERENCES

        assertFalse(state.leavingEndsTheVisit(Screen.MUSEUM))
        assertTrue("the visit stays open", state.leavingEndsTheVisit(Screen.PREFERENCES))
    }

    @Test
    fun `a visit ends once`() {
        // leavingEndsTheVisit spends its answer: a second Done is an ordinary step back
        val state = WinampState()
        state.arrivedAt = Screen.PREFERENCES

        assertTrue(state.leavingEndsTheVisit(Screen.PREFERENCES))
        assertFalse(state.leavingEndsTheVisit(Screen.PREFERENCES))
    }

    @Test
    fun `opening a screen from inside the app is not a visit`() {
        assertFalse(WinampState().leavingEndsTheVisit(Screen.PREFERENCES))
    }

    @Test
    fun `walking out of the app ends the visit too`() {
        // pressing Home on a screen the widget opened clears the arming, so a later
        // Done on a screen opened from inside the app stays in the app
        val state = WinampState()
        state.arrivedAt = Screen.PREFERENCES

        state.leftTheApp()

        assertFalse(state.leavingEndsTheVisit(Screen.PREFERENCES))
    }
}
