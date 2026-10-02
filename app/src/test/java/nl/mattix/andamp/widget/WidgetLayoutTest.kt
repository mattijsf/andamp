// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.SHADE_H
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which window the widget picks for a box, and at what scale. The art cannot
 * reflow, so the choice is between the whole player and its shade.
 */
class WidgetLayoutTest {
    @Test
    fun `a box the size of the player draws the player`() {
        val layout = WidgetLayout.choose(MAIN_W, MAIN_H)

        assertFalse(layout.shaded)
        assertEquals(1f, layout.factor)
    }

    @Test
    fun `a box twice the player draws it twice as big`() {
        val layout = WidgetLayout.choose(MAIN_W * 2, MAIN_H * 2)

        assertFalse(layout.shaded)
        assertEquals(2f, layout.factor)
    }

    @Test
    fun `the tighter of the two directions decides the scale`() {
        // wide and short: the height is what there is least of, and drawing to
        // the width would run the player off the bottom of the box
        val layout = WidgetLayout.choose(MAIN_W * 4, MAIN_H * 2)

        assertFalse(layout.shaded)
        assertEquals(2f, layout.factor)
    }

    @Test
    fun `a box too short for the player falls back to the shade`() {
        // one row of a launcher grid: room for a strip, not for a window
        val layout = WidgetLayout.choose(MAIN_W * 2, MAIN_H - 1)

        assertTrue(layout.shaded)
        assertEquals(layout.px(SHADE_H), layout.heightPx)
    }

    @Test
    fun `the whole window wins whenever it fits at all`() {
        // the shade is a fallback: a box one pixel too short gets it
        assertFalse(WidgetLayout.choose(MAIN_W, MAIN_H).shaded)
        assertTrue(WidgetLayout.choose(MAIN_W, MAIN_H - 1).shaded)
    }

    @Test
    fun `the scale is never zero, however small the box`() {
        // a scale of zero is a bitmap of no pixels, which crashes
        listOf(0 to 0, 1 to 1, 100 to 20, MAIN_W - 1 to MAIN_H).forEach { (w, h) ->
            assertTrue("box ${w}x$h has a scale of at least 1", WidgetLayout.choose(w, h).factor >= 1f)
        }
    }

    @Test
    fun `a wide box 90 pixels high gets the shade and stays inside it`() {
        val portraitHeight = MAIN_H * 3
        val landscapeHeight = 90

        val fits = WidgetLayout.choose(MAIN_W * 3, minOf(portraitHeight, landscapeHeight))

        assertTrue("a 90px row gets the shade", fits.shaded)
        assertTrue("the shade fits the 90px row", fits.heightPx <= landscapeHeight)
    }

    @Test
    fun `what it asks to draw always fits what it was given`() {
        // never a bitmap larger than the box, so the host never has to
        // squeeze one. The provider declares minWidth=275dp, so no box is
        // narrower than MAIN_W
        for (w in listOf(MAIN_W, 300, 550, 800, 1100)) {
            for (h in listOf(SHADE_H, 40, MAIN_H, 200, 300)) {
                val layout = WidgetLayout.choose(w, h)
                assertTrue("box ${w}x$h fits its drawing: ${layout.widthPx}x${layout.heightPx}", layout.widthPx <= w)
                assertTrue("box ${w}x$h fits its drawing: ${layout.widthPx}x${layout.heightPx}", layout.heightPx <= h)
            }
        }
    }

    @Test
    fun `filling reaches the edge the whole-pixel scale stops short of`() {
        // 24 pixels of cell that whole-number scaling cannot use
        val box = MAIN_W * 3 + 24
        val whole = WidgetLayout.choose(box, MAIN_H * 4)
        val fill = WidgetLayout.choose(box, MAIN_H * 4, fill = true)

        assertEquals("whole pixels leave the remainder", MAIN_W * 3, whole.widthPx)
        assertEquals("filling spends the remainder", box, fill.widthPx)
    }

    @Test
    fun `filling keeps the player's shape, and never overflows the box`() {
        // uniform, not stretched; the height is what binds here
        val height = MAIN_H * 3 + 2
        val fill = WidgetLayout.choose(MAIN_W * 8, height, fill = true)

        assertEquals("the tight direction is filled", height, fill.heightPx)
        assertTrue("the loose one is not stretched to it", fill.widthPx < MAIN_W * 8)
        assertEquals("the fill keeps the ratio", MAIN_W.toFloat() / MAIN_H, fill.widthPx.toFloat() / fill.heightPx, 0.01f)
    }

    @Test
    fun `a fractional scale draws some columns wider than others`() {
        // at 3.09 pixels per pixel a virtual column is three pixels wide or
        // four depending on where it starts; rounding each width on its own
        // would leave seams between parts that have to touch
        val layout = WidgetLayout.choose(MAIN_W * 3 + 24, MAIN_H * 4, fill = true)
        val widths = (0 until MAIN_W).map { layout.span(it, 1) }.toSet()

        assertEquals("columns are three or four pixels wide", setOf(3, 4), widths)
        assertEquals("the columns add up to the whole picture", layout.widthPx, (0 until MAIN_W).sumOf { layout.span(it, 1) })
    }
}
