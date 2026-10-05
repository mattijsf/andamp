// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collapsing a window takes the windows glued under it along.
 *
 * Shade the player and the equalizer comes up to meet it; otherwise the stack would have
 * a 102px hole where the player used to be.
 */
class ShadeDockTest {
    private val screenH = 533

    /** A stack: player 0..116, equalizer flush under it, playlist flush under that. */
    private fun stack(): WinampState =
        WinampState().apply {
            screenW = MAIN_W
            this.screenH = this@ShadeDockTest.screenH
            windowRects[WindowStore.MAIN] = IntRect(0, 0, MAIN_W, MAIN_H)
            windowRects[WindowStore.EQ] = IntRect(0, MAIN_H, EQ_W, MAIN_H + EQ_H)
            windowRects[WindowStore.PLAYLIST] = IntRect(0, MAIN_H + EQ_H, PL_W, MAIN_H + EQ_H + 290)
            // all placed by hand (a drag, or a snap), so nothing recomputes
            // their row for them
            mainOffset = IntOffset(0, 0 - (screenH - MAIN_H) / 2)
            eqOffset = IntOffset(0, MAIN_H - (screenH - EQ_H) / 2)
            plOffset = IntOffset(0, MAIN_H + EQ_H - (screenH - 290) / 2)
        }

    /** What the next layout pass would publish, given the offsets in state. */
    private fun relayout(
        s: WinampState,
        mainH: Int,
        eqH: Int,
        plH: Int,
    ) {
        fun rect(
            offset: IntOffset?,
            height: Int,
            width: Int,
        ): IntRect {
            val top = (s.screenH - height) / 2 + (offset?.y ?: 0)
            return IntRect(0, top, width, top + height)
        }
        s.windowRects[WindowStore.MAIN] = rect(s.mainOffset, mainH, MAIN_W)
        s.windowRects[WindowStore.EQ] = rect(s.eqOffset, eqH, EQ_W)
        s.windowRects[WindowStore.PLAYLIST] = rect(s.plOffset, plH, PL_W)
    }

    private fun topOf(
        state: WinampState,
        offset: IntOffset,
        height: Int,
    ) = (state.screenH - height) / 2 + offset.y

    @Test
    fun `shading the player brings the windows under it up with it`() {
        val s = stack()

        s.setShaded(WindowStore.MAIN, true, MAIN_H)

        assertEquals("the equalizer comes up under the shaded player", SHADE_H, topOf(s, s.eqOffset!!, EQ_H))
        assertEquals("the playlist comes up under the equalizer", SHADE_H + EQ_H, topOf(s, s.plOffset!!, 290))
    }

    @Test
    fun `in a locked stack the layout moves the windows, and the places they float at are kept`() {
        val s = stack().apply { doubleSize = true }
        val before = listOf(s.mainOffset, s.eqOffset, s.plOffset)

        s.setShaded(WindowStore.MAIN, true, MAIN_H)

        assertTrue("the player collapses", s.mainShaded)
        assertEquals(before, listOf(s.mainOffset, s.eqOffset, s.plOffset))
    }

    @Test
    fun `expanding it puts them back`() {
        val s = stack()
        val eqBefore = s.eqOffset
        val plBefore = s.plOffset

        s.setShaded(WindowStore.MAIN, true, MAIN_H)
        relayout(s, SHADE_H, EQ_H, 290)
        s.setShaded(WindowStore.MAIN, false, MAIN_H)

        assertEquals(eqBefore, s.eqOffset)
        assertEquals(plBefore, s.plOffset)
    }

    @Test
    fun `shading the equalizer leaves the player alone and lifts the playlist`() {
        val s = stack()
        val mainBefore = s.mainOffset

        s.setShaded(WindowStore.EQ, true, EQ_H)

        assertEquals("the player stays put", mainBefore, s.mainOffset)
        assertEquals("the playlist comes up under the shaded equalizer", MAIN_H + SHADE_H, topOf(s, s.plOffset!!, 290))
    }

    @Test
    fun `a window that is not glued to it stays where it is`() {
        val s = stack()
        // the playlist floats a long way below, touching nothing
        s.windowRects[WindowStore.PLAYLIST] = IntRect(0, 400, PL_W, 690)
        s.plOffset = IntOffset(0, 400 - (screenH - 290) / 2)
        val plBefore = s.plOffset

        s.setShaded(WindowStore.MAIN, true, MAIN_H)

        assertEquals(plBefore, s.plOffset)
    }

    @Test
    fun `a window still following the stack is left to follow it`() {
        val s = stack()
        s.plOffset = null // never moved: its row is worked out from the stack

        s.setShaded(WindowStore.MAIN, true, MAIN_H)

        assertEquals(null, s.plOffset)
    }

    @Test
    fun `a window hanging off a side-docked neighbor is left alone`() {
        // main and the equalizer side by side, the playlist glued under the
        // equalizer only: collapsing main must not drag the playlist up and
        // away from the window it is actually glued to
        val s =
            WinampState().apply {
                screenW = 560
                screenH = this@ShadeDockTest.screenH
                windowRects[WindowStore.MAIN] = IntRect(0, 0, MAIN_W, MAIN_H)
                windowRects[WindowStore.EQ] = IntRect(MAIN_W, 0, MAIN_W + EQ_W, EQ_H)
                windowRects[WindowStore.PLAYLIST] = IntRect(MAIN_W, EQ_H, MAIN_W + PL_W, EQ_H + 290)
                mainOffset = IntOffset(0, 0 - (screenH - MAIN_H) / 2)
                eqOffset = IntOffset(MAIN_W, 0 - (screenH - EQ_H) / 2)
                plOffset = IntOffset(MAIN_W, EQ_H - (screenH - 290) / 2)
            }
        val eqBefore = s.eqOffset
        val plBefore = s.plOffset

        s.setShaded(WindowStore.MAIN, true, MAIN_H)

        assertEquals("the side neighbor stays put", eqBefore, s.eqOffset)
        assertEquals("a window glued to the side neighbor stays put", plBefore, s.plOffset)
    }

    @Test
    fun `a side neighbor that dips into the slack band stays put`() {
        // its top is a pixel above the collapsing window's bottom: docked
        // beside it, not under it, whatever a line test would say
        val s =
            WinampState().apply {
                screenW = 560
                screenH = this@ShadeDockTest.screenH
                windowRects[WindowStore.MAIN] = IntRect(0, 0, MAIN_W, MAIN_H)
                windowRects[WindowStore.EQ] = IntRect(MAIN_W, MAIN_H - 1, MAIN_W + EQ_W, MAIN_H - 1 + EQ_H)
                mainOffset = IntOffset(0, 0 - (screenH - MAIN_H) / 2)
                eqOffset = IntOffset(MAIN_W, MAIN_H - 1 - (screenH - EQ_H) / 2)
            }
        val eqBefore = s.eqOffset

        s.setShaded(WindowStore.MAIN, true, MAIN_H)

        assertEquals(eqBefore, s.eqOffset)
    }

    @Test
    fun `a carried window is kept inside the safe area`() {
        val s = stack()
        s.safeBottom = 200 // a short safe area: expanding pushes the playlist's bar past it

        s.setShaded(WindowStore.MAIN, true, MAIN_H) // collapse first, so expanding pushes down
        relayout(s, SHADE_H, EQ_H, 290)
        s.setShaded(WindowStore.MAIN, false, MAIN_H)

        val top = topOf(s, s.plOffset!!, 290)
        assertTrue("the playlist's bar stays in the safe area: top=$top", top + Dest.PL_TOP_H <= s.safeBottom)
    }
}
