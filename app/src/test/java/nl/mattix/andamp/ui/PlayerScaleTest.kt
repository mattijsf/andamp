// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScaleTest {
    @Test
    fun `a phone held upright is decided by its width`() {
        // a 1080x2424 phone and an 18:9 phone, under their status bars
        assertEquals(3, playerScale(1080, 2424 - 142))
        assertEquals(3, playerScale(1080, 2160 - 142))
        assertEquals(2, playerScale(720, 1600 - 96))
    }

    @Test
    fun `a tablet on its side is decided by its height and the stack fits`() {
        val scale = playerScale(2560, 1600 - 48)
        assertEquals(3, scale)
        assertTrue("the smallest stack fits", SMALLEST_STACK_H * scale <= 1600 - 48)
    }

    @Test
    fun `a desktop floats the player rather than filling the screen with it`() {
        assertEquals(3, playerScale(2560, 1440 - 48))
        assertEquals(2, playerScale(1920, 1080 - 32))
    }

    @Test
    fun `a screen too small for either still draws at one`() {
        assertEquals(1, playerScale(200, 300))
    }
}
