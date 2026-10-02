// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.skin.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One visualizer in two rooms, and the small room's measurements.
 *
 * The strip's numbers are webamp's: `.shade #visualizer` sits at 79,5
 * (css/main-window.css) and renders five rows high with the lattice skipped
 * (js/components/Vis.tsx). Its width stops short of the shaded clock at x=127; a wider
 * strip would draw bars under the time.
 */
class VisBoxTest {
    @Test
    fun `the player's own visualizer is the size the art leaves for it`() {
        assertEquals(Dest.VIS_W, VisBox.FULL.w)
        assertEquals(Dest.VIS_H, VisBox.FULL.h)
        assertTrue("the big one keeps its lattice", VisBox.FULL.dots)
        assertEquals("one bar per measured band", 19, VisBox.FULL.bars)
    }

    @Test
    fun `the shaded strip is five rows tall, as Winamp's was`() {
        assertEquals(5, VisBox.SHADE.h)
    }

    /**
     * Ten bars, each three columns lit and one skipped, which is what Winamp's "wide"
     * bandwidth does whatever room it is given.
     */
    @Test
    fun `the strip's bars are the player's own width, and there are ten`() {
        assertEquals(10, VisBox.SHADE.bars)
        assertEquals(VisBox.FULL.barW, VisBox.SHADE.barW)
        assertEquals(VisBox.FULL.pitch, VisBox.SHADE.pitch)
    }

    /** The bars have to start inside the room they are given. */
    @Test
    fun `every bar starts inside the strip`() {
        val lastStart = (VisBox.SHADE.bars - 1) * VisBox.SHADE.pitch

        assertTrue("bar ${VisBox.SHADE.bars} starts inside the strip", lastStart < VisBox.SHADE.w)
    }

    @Test
    fun `the big window's bars fit too`() {
        val used = (VisBox.FULL.bars - 1) * VisBox.FULL.pitch + VisBox.FULL.barW

        assertTrue(used <= VisBox.FULL.w)
    }

    /** The clock is at 127; a strip that reaches it draws under the time. */
    @Test
    fun `the strip stops short of the shaded clock`() {
        assertTrue(
            "the strip stops short of the clock",
            MAIN_SHADE_VIS.x + VisBox.SHADE.w <= SHADE_CLOCK_X,
        )
    }

    /** The lattice is what the strip loses; the peak caps are not. */
    @Test
    fun `the strip has no room for the lattice`() {
        assertFalse(VisBox.SHADE.dots)
    }

    private companion object {
        /** webamp's `.mini-time` left, which the shaded clock is drawn at. */
        const val SHADE_CLOCK_X = 127
    }
}
