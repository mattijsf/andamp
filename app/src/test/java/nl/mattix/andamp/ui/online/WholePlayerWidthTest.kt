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
}
