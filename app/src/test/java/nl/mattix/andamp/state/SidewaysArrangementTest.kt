// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning the phone shows the arrangement made for that way round, and turning it back
 * shows the other one as it was left.
 */
class SidewaysArrangementTest {
    @Test
    fun `turning the phone swaps every window's place and turning back restores it`() {
        val s = WinampState()
        s.mainOffset = IntOffset(0, -200)
        s.plSegments = 6

        s.sideways = true

        assertNull("sideways starts where the layout puts it", s.mainOffset)
        assertNull(s.plSegments)
        s.mainOffset = IntOffset(-300, 0)

        s.sideways = false

        assertEquals(IntOffset(0, -200), s.mainOffset)
        assertEquals(6, s.plSegments)
        s.sideways = true
        assertEquals(IntOffset(-300, 0), s.mainOffset)
    }

    @Test
    fun `a shade is part of the arrangement`() {
        val s = WinampState()
        s.sideways = true
        s.mainShaded = true

        s.sideways = false

        assertEquals(false, s.mainShaded)
    }

    @Test
    fun `whether a window is open is the window's own, whichever way round`() {
        val s = WinampState()
        s.eqVisible = false

        s.sideways = true

        assertEquals(false, s.eqVisible)
        s.eqVisible = true
        s.sideways = false
        assertEquals(true, s.eqVisible)
    }

    @Test
    fun `what is remembered holds both arrangements`() {
        val s = WinampState()
        s.libraryOffset = IntOffset(10, 20)
        s.sideways = true
        s.libraryOffset = IntOffset(-30, 0)

        val memory = s.placementOf(WindowStore.LIBRARY)!!.asMemory()

        assertEquals(WindowArrangement(x = 10, y = 20, cols = 0), memory.upright)
        assertEquals(WindowArrangement(x = -30, y = 0, cols = 0), memory.sideways)
    }
}
