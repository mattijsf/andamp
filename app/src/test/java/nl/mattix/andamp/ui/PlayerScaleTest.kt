// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import nl.mattix.andamp.ui.window.MAIN_W
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

    @Test
    fun `a player that does not fill the screen is laid out on the screen itself`() {
        val viewport = playerViewport(1080, 2424 - 142, fillScreen = false)

        assertEquals(PlayerViewport(scale = playerScale(1080, 2424 - 142), shrink = 1f), viewport)
        assertEquals(1080, viewport.inner(1080))
    }

    @Test
    fun `filling a phone lays the player out at the next whole scale, exactly as wide as the player`() {
        val viewport = playerViewport(1080, 2424 - 142, fillScreen = true)

        assertEquals(4, viewport.scale)
        assertEquals(MAIN_W * 4, viewport.inner(1080))
        assertEquals(1080f / 1100f, viewport.shrink, 0f)
    }

    @Test
    fun `a screen that is a whole number of players wide is not shrunk`() {
        assertEquals(PlayerViewport(scale = 4, shrink = 1f), playerViewport(1100, 2400, fillScreen = true))
    }

    @Test
    fun `filling a tablet on its side is decided by its height and the stack fits exactly`() {
        val height = 1600 - 48
        val viewport = playerViewport(2560, height, fillScreen = true)

        assertEquals(4, viewport.scale)
        assertEquals(SMALLEST_STACK_H * 4, viewport.inner(height))
        assertTrue("room is left at the sides", viewport.inner(2560) / viewport.scale > MAIN_W)
    }

    @Test
    fun `a filled screen never shows less than the whole player`() {
        for (width in 300..3000 step 7) {
            for (height in listOf(width / 2, width, width * 2)) {
                val viewport = playerViewport(width, height, fillScreen = true)
                val label = "$width x $height"
                assertTrue(label, viewport.shrink <= 1f)
                assertTrue(label, viewport.shrink > (viewport.scale - 1f) / viewport.scale)
                assertTrue(label, viewport.inner(width) / viewport.scale >= MAIN_W)
                assertTrue(label, viewport.inner(height) / viewport.scale >= SMALLEST_STACK_H)
            }
        }
    }

    @Test
    fun `a screen smaller than the player is filled by shrinking scale one`() {
        val viewport = playerViewport(200, 600, fillScreen = true)

        assertEquals(1, viewport.scale)
        assertEquals(MAIN_W, viewport.inner(200))
    }
}
