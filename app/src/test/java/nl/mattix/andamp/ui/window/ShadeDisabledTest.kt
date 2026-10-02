// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A listener who has asked for windows that stay open.
 *
 * The two ways to shade a window are easy to hit by accident: a 9x9 button beside Close,
 * and a double tap on the bar the window is dragged by. With the setting off, every way in
 * is refused and what was already folded is opened again.
 */
class ShadeDisabledTest {
    private val state =
        WinampState().apply {
            screenH = 800
            shadeEnabled = false
        }

    @Test
    fun `nothing can collapse a window while it is switched off`() {
        state.setShaded(WindowStore.MAIN, true, MAIN_H)
        state.setShaded(WindowStore.EQ, true, EQ_H)
        state.setShaded(WindowStore.PLAYLIST, true, 290)

        assertFalse(state.mainShaded)
        assertFalse(state.eqShaded)
        assertFalse(state.plShaded)
    }

    /** A window left folded in an earlier session opens again. */
    @Test
    fun `windows left folded are opened`() {
        val left = WinampState().apply { screenH = 800 }
        left.setShaded(WindowStore.MAIN, true, MAIN_H)
        left.setShaded(WindowStore.PLAYLIST, true, 290)
        assertTrue(left.mainShaded)

        left.shadeEnabled = false
        left.unshadeEverything(playlistExpandedH = 290)

        assertFalse(left.mainShaded)
        assertFalse(left.plShaded)
    }

    @Test
    fun `it stays Winamp's own behavior until somebody says otherwise`() {
        val fresh = WinampState().apply { screenH = 800 }

        fresh.setShaded(WindowStore.MAIN, true, MAIN_H)

        assertTrue(fresh.mainShaded)
    }
}
