// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.setShaded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating layout and the locked stack are two layouts of the same windows. Which
 * windows are collapsed, and which is in front, belongs to each of them: what is done in one
 * is not found done in the other.
 */
class LayoutOwnStateTest {
    private fun state() = WinampState().apply { screenH = 800 }

    @Test
    fun `a window collapsed in the locked stack still stands open in the floating layout`() {
        val s = state()
        s.stackLocked = true

        s.setShaded(WindowStore.EQ, true, EQ_H)
        assertTrue(s.eqShaded)

        s.stackLocked = false
        assertFalse("the floating equalizer was not touched", s.eqShaded)
        assertEquals(false, s.placementOf(WindowStore.EQ)?.asMemory()?.shaded ?: false)
    }

    @Test
    fun `a window collapsed in the floating layout still stands open in the locked stack`() {
        val s = state()

        s.setShaded(WindowStore.MAIN, true, MAIN_H)
        s.stackLocked = true

        assertFalse(s.mainShaded)
        assertEquals(emptySet<String>(), s.stackShaded)
    }

    @Test
    fun `each layout finds its own collapsed windows again`() {
        val s = state()
        s.setShaded(WindowStore.MAIN, true, MAIN_H)
        s.stackLocked = true
        s.setShaded(WindowStore.EQ, true, EQ_H)

        assertEquals("the stack: only the equalizer", listOf(false, true), listOf(s.mainShaded, s.eqShaded))
        s.stackLocked = false
        assertEquals("floating: only the player", listOf(true, false), listOf(s.mainShaded, s.eqShaded))
        s.stackLocked = true
        assertEquals(setOf(WindowStore.EQ), s.stackShaded)
    }

    @Test
    fun `expanding a window in the locked stack takes it out of the stack's own set`() {
        val s = state()
        s.stackLocked = true
        s.setShaded(WindowStore.PLAYLIST, true, 290)

        s.setShaded(WindowStore.PLAYLIST, false, 290)

        assertEquals(emptySet<String>(), s.stackShaded)
    }

    @Test
    fun `a window brought to the front in the locked stack keeps its place in the floating layout`() {
        val s = state()
        val floating = s.windowOrder
        s.stackLocked = true

        s.raiseWindow(WindowStore.MAIN)

        assertEquals("the stack has the player in front", WindowStore.MAIN, s.shownOrder.last())
        assertEquals("the floating order is as it was", floating, s.windowOrder)
        s.stackLocked = false
        assertEquals(floating, s.shownOrder)
    }

    @Test
    fun `a window brought to the front while floating keeps its place in the locked stack`() {
        val s = state()
        s.stackLocked = true
        val stacked = s.shownOrder
        s.stackLocked = false

        s.raiseWindow(WindowStore.EQ)

        assertEquals(WindowStore.EQ, s.windowOrder.last())
        s.stackLocked = true
        assertEquals(stacked, s.shownOrder)
    }
}
