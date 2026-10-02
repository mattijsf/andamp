// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGateTest {
    private val ready = OverlayGate(wanted = true, permitted = true, appInFront = false)

    @Test
    fun `everything in place shows the floating player`() {
        assertTrue(ready.showing)
    }

    @Test
    fun `the player does not float over itself`() {
        assertFalse(ready.copy(appInFront = true).showing)
    }

    @Test
    fun `an unasked-for overlay stays away, permission or not`() {
        assertFalse(ready.copy(wanted = false).showing)
    }

    @Test
    fun `permission taken away in system settings takes the overlay with it`() {
        assertFalse(ready.copy(permitted = false).showing)
    }

    @Test
    fun `the close button hides it without turning the preference off`() {
        val closed = ready.dismiss()

        assertFalse(closed.showing)
        assertTrue("the close button leaves the preference on", closed.wanted)
    }

    @Test
    fun `coming back to the app arms it for the next time`() {
        val closed = ready.dismiss()

        val armed = closed.returnedToApp()

        assertFalse("nothing floats while the app is in front", armed.showing)
        assertTrue(armed.leftApp().showing)
    }

    @Test
    fun `leaving the app again does not undo a close on its own`() {
        val closed = ready.dismiss()

        assertFalse(closed.leftApp().showing)
    }
}
