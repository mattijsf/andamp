// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class WindowBoundsTest {
    // a 275x150 window on a 275x800 virtual screen
    private val w = 275
    private val h = 150
    private val screenW = 275
    private val screenH = 800

    private val titleH = 20

    private fun clamp(
        x: Int,
        y: Int,
    ) = WindowBounds.clamp(IntOffset(x, y), w, h, screenW, screenH, titleH)

    private fun top(y: Int) = (screenH - h) / 2 + clamp(0, y).y

    @Test
    fun `a centered window is left alone`() {
        assertEquals(IntOffset.Zero, clamp(0, 0))
    }

    @Test
    fun `a modest drag is left alone`() {
        assertEquals(IntOffset(40, -60), clamp(40, -60))
    }

    @Test
    fun `a window can hang exactly half off each edge`() {
        // half off the right: its center sits on the screen edge
        assertEquals(w / 2, clamp(9999, 0).x)
        assertEquals(-w / 2, clamp(-9999, 0).x)
    }

    @Test
    fun `the title bar can never leave the screen`() {
        // dragged as far up as possible: the title bar sits at the very top
        assertEquals(0, top(-100_000))
        // and as far down: the whole title bar is still visible
        assertEquals(screenH - titleH, top(100_000))
    }

    @Test
    fun `a window can never be dragged fully out of reach`() {
        val far = clamp(100_000, 100_000)
        assertEquals(true, abs(far.x) <= (screenW - w) / 2 + w / 2)
    }

    @Test
    fun `a window wider than the screen still cannot vanish`() {
        val wide = WindowBounds.clamp(IntOffset(10_000, 0), 400, h, screenW, screenH, titleH)
        assertEquals(true, wide.x <= 400 / 2)
    }

    @Test
    fun `a window taller than the screen still keeps its title bar reachable`() {
        val tall = WindowBounds.clamp(IntOffset(0, -100_000), w, 2000, screenW, screenH, titleH)
        val topOfTall = (screenH - 2000) / 2 + tall.y
        assertEquals(0, topOfTall)
    }

    @Test
    fun `the title bar stays out of the gesture bar, not merely on screen`() {
        // clamping to the raw screen height would let the window park its
        // title bar under the system gesture bar, where it cannot be grabbed
        val gestureBar = 24
        val safeBottom = screenH - gestureBar
        val far =
            WindowBounds.clamp(
                IntOffset(0, 100_000),
                w,
                h,
                screenW,
                screenH,
                titleH,
                safeTop = 0,
                safeBottom = safeBottom,
            )
        val topEdge = (screenH - h) / 2 + far.y
        assertEquals(safeBottom - titleH, topEdge)
    }

    @Test
    fun `the title bar stays below a status bar inset too`() {
        val statusBar = 40
        val far =
            WindowBounds.clamp(
                IntOffset(0, -100_000),
                w,
                h,
                screenW,
                screenH,
                titleH,
                safeTop = statusBar,
                safeBottom = screenH,
            )
        assertEquals(statusBar, (screenH - h) / 2 + far.y)
    }
}
