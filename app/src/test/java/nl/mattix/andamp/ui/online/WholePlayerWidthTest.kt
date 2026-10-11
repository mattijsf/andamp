// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How wide a player is drawn, wherever it is drawn.
 *
 * The preview lands on whole pixels, so everything sharing a frame with it,
 * such as the museum's picture that holds the space, has to be the same width.
 */
class WholePlayerWidthTest {
    @Test
    fun `it is the widest whole multiple that fits`() {
        assertEquals(825, wholePlayerWidth(1080))
        assertEquals(825, wholePlayerWidth(960))
        assertEquals(550, wholePlayerWidth(824))
    }

    @Test
    fun `an exact fit is not rounded away`() {
        assertEquals(1100, wholePlayerWidth(1100))
    }

    @Test
    fun `a screen too narrow for one still gets one`() {
        assertEquals(275, wholePlayerWidth(100))
    }

    /**
     * A skin is shown at its screenshot's shape, 275 by 348, so a width is also a height. Where
     * the room to stand in is the smaller of the two, it decides.
     */
    @Test
    fun `with little room to stand in it is the widest whose height still fits`() {
        // 2560 across would hold nine, but 1184 down holds three: 3 x 348 = 1044
        assertEquals(825, wholePlayerWidth(2560, roomPx = 1184))
        // one pixel short of four
        assertEquals(825, wholePlayerWidth(2560, roomPx = 4 * 348 - 1))
        assertEquals(1100, wholePlayerWidth(2560, roomPx = 4 * 348))
    }

    @Test
    fun `with room to spare the width decides, as before`() {
        assertEquals(825, wholePlayerWidth(1080, roomPx = 1763))
    }

    @Test
    fun `no room at all still gets one`() {
        assertEquals(275, wholePlayerWidth(2560, roomPx = 100))
        assertEquals(275, wholePlayerWidth(2560, roomPx = -50))
    }
}
