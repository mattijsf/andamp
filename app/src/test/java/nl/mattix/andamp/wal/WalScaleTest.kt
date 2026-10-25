// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How large a modern skin is drawn, and which skins are too large to show at all. */
class WalScaleTest {
    private val phone = IntSize(1080, 2000)

    @Test
    fun `skins the size of a desktop window show`() {
        // Bento's maximized window, and MMD3 with room on its right for its panel
        assertNull(WalScale.unfit(800 to 600, 800 to 600, baseW = 0, box = phone))
        assertNull(WalScale.unfit(800 to 161, 800 to 536, baseW = 410, box = phone))
    }

    @Test
    fun `a main window or a stack past the pixel cap does not show, with or without a box`() {
        val cap = WalScale.MAX_PX
        assertNull(WalScale.unfit(cap to 100, cap to 100, baseW = 0, box = null))
        assertNotNull(WalScale.unfit(cap + 1 to 100, cap + 1 to 100, baseW = 0, box = null))
        assertNotNull("the stack", WalScale.unfit(400 to 100, 400 to cap + 1, baseW = 0, box = null))
        assertNotNull("FuzzTest's huge sizes", WalScale.unfit(2_000_000_000 to 2_000_000_000, 0 to 0, baseW = 0, box = phone))
    }

    @Test
    fun `a skin that fits its box only below the smallest scale does not show`() {
        // 400 wide in 100: a quarter, the smallest scale shown
        assertEquals(WalScale.MIN_SCALE, WalScale.fit(IntSize(100, 1000), 400 to 100, baseW = 0))
        assertNull(WalScale.unfit(400 to 100, 400 to 100, baseW = 0, box = IntSize(100, 1000)))
        assertNotNull(WalScale.unfit(400 to 100, 400 to 100, baseW = 0, box = IntSize(99, 1000)))
        // the visible width at the start sets the scale, not the whole window
        assertNull(WalScale.unfit(800 to 100, 800 to 100, baseW = 396, box = IntSize(99, 1000)))
    }

    @Test
    fun `a box not laid out yet turns no skin down`() {
        // a Winamp-sized skin, before the player's box has a height or a width
        assertNull(WalScale.unfit(275 to 116, 275 to 116, baseW = 275, box = IntSize(0, 480)))
        assertNull(WalScale.unfit(275 to 116, 275 to 116, baseW = 275, box = IntSize(1080, 0)))
        assertNull(WalScale.unfit(275 to 116, 275 to 116, baseW = 275, box = IntSize.Zero))
        assertNotNull("still too large without a box", WalScale.unfit(5000 to 116, 5000 to 116, baseW = 0, box = IntSize.Zero))
    }

    @Test
    fun `a main window with no size yet shows`() {
        assertNull(WalScale.unfit(0 to 0, 0 to 0, baseW = 0, box = phone))
    }

    @Test
    fun `upright, the main window sets a whole scale and the stack hangs from the top, centered by its content`() {
        val place = WalScale.place(WalFit(1080, 2000, 3f, false), 275 to 116, 275, 275, 275 to 400)

        assertEquals(3f, place.scale)
        // whole device pixels: 127.5 floors to 127
        assertEquals(127f, place.left)
        assertEquals(0f, place.top)
    }

    @Test
    fun `an upright stack taller than the box is shrunk to its height and centered`() {
        val place = WalScale.place(WalFit(1080, 1000, 3f, false), 275 to 116, 275, 275, 275 to 600)

        assertEquals(1000f / 600, place.scale)
        assertEquals(kotlin.math.floor((1080 - 275 * place.scale) / 2), place.left)
        assertEquals(0f, place.top)
        assertTrue("the whole stack is in the box", 600 * place.scale <= 1000f)
    }

    @Test
    fun `sideways, the whole stack fits both ways and is centered both ways`() {
        val place = WalScale.place(WalFit(2400, 1080, 3f, true), 400 to 100, 400, 400, 400 to 300)

        // 1080 / 300 is 3.6: whole from 2x up
        assertEquals(3f, place.scale)
        assertEquals((2400 - 400 * 3) / 2f, place.left)
        assertEquals((1080 - 300 * 3) / 2f, place.top)
    }

    @Test
    fun `sideways, a stack the box holds only below 2x keeps the fractional scale, centered both ways`() {
        val place = WalScale.place(WalFit(2400, 1080, 3f, true), 400 to 100, 400, 400, 400 to 720)

        assertEquals(1.5f, place.scale)
        assertEquals((2400 - 400 * 1.5f) / 2f, place.left)
        assertEquals(0f, place.top)
    }

    @Test
    fun `a stack that shows only part of its height keeps the scale of the whole and is centered by what shows`() {
        // Winamp Modern: a 280 px main window of which 164 px show while its drawer is closed
        val sideways = WalScale.place(WalFit(2400, 1080, 3f, true), 354 to 280, 354, 354, 354 to 280, drawn = 354 to 164)
        assertEquals(3f, sideways.scale)
        assertEquals((1080 - 164 * 3) / 2f, sideways.top)

        val shrunk = WalScale.place(WalFit(1080, 1000, 3f, false), 275 to 116, 275, 275, 275 to 600, drawn = 275 to 500)
        assertEquals(1000f / 600, shrunk.scale)
        assertEquals(kotlin.math.floor((1000 - 500 * shrunk.scale) / 2), shrunk.top)
    }

    @Test
    fun `upright, a drawer that runs past the screen shrinks the skin once it rests`() {
        // MMD3 on the Pixel: 381 px show at the start, which sets 2x; its drawer slid out shows all 583
        val pixel = WalFit(960, 1908, 2.25f, false)
        val sliding = WalScale.place(pixel, 583 to 216, 381, 583, 583 to 476, drawn = 583 to 476, widest = 381)
        assertEquals("while it slides, the width before the slide holds the scale", 2f, sliding.scale)

        val resting = WalScale.place(pixel, 583 to 216, 381, 583, 583 to 476, drawn = 583 to 476)
        assertEquals(960f / 583, resting.scale)
        assertEquals(0f, resting.left)
        assertTrue("the whole drawer is on screen", 583 * resting.scale <= 960f)
    }

    @Test
    fun `it grows back when the drawer closes`() {
        val pixel = WalFit(960, 1908, 2.25f, false)
        val closing = WalScale.place(pixel, 583 to 216, 381, 381, 583 to 476, drawn = 381 to 476, widest = 583)
        assertEquals("while the drawer slides back, the scale holds", 960f / 583, closing.scale)

        val closed = WalScale.place(pixel, 583 to 216, 381, 381, 583 to 476, drawn = 381 to 476)
        assertEquals(2f, closed.scale)
        assertEquals((960 - 381 * 2) / 2f, closed.left)
    }

    @Test
    fun `on a small phone the drawer fits, CornerAmp stays at a whole 2x, and Bento fills the width`() {
        // a 360 dp phone: 720 px wide, about 1136 px high for the skin
        val small = WalFit(720, 1136, 2f, false)

        val mmd3 = WalScale.place(small, 583 to 216, 381, 381, 583 to 216, drawn = 381 to 216)
        assertEquals("closed: from the 381 px that show", 720f / 381, mmd3.scale)
        val open = WalScale.place(small, 583 to 216, 381, 583, 583 to 216)
        assertEquals("open: its whole 583 px inside the box", 720f / 583, open.scale)
        assertTrue(583 * open.scale <= 720f)

        val corner = WalScale.place(small, 246 to 228, 246, 246, 246 to 228)
        assertEquals("2.9x would fit, and whole scales from 2x up keep the pixels crisp", 2f, corner.scale)
        assertEquals((720 - 246 * 2) / 2f, corner.left)

        // Bento is sized to the box at the scale its minimum width fits: 633 px in 720
        val s = WalScale.of(IntSize(720, 1136), 633, 492)
        assertEquals(720f / 633, s)
        val w = maxOf(633, (720 / s).toInt())
        val h = maxOf(492, (1136 / s).toInt())
        val bento = WalScale.place(small, w to h, 0, w, w to h)
        assertEquals("its size fills the box, and a rounding does not shrink it", s, bento.scale)
        assertEquals(0f, bento.left)
    }

    @Test
    fun `a window docked wider than the main one is kept inside the box`() {
        // CornerAmp's playlist is at least 275 px wide under a 246 px main window, here in a box 500 px wide
        val narrow = WalFit(500, 1136, 2f, false)
        val place = WalScale.place(narrow, 246 to 228, 246, 246, 275 to 428)

        assertEquals(500f / 275, place.scale)
        assertEquals("moved left so the playlist ends inside the box", 0f, place.left)
        assertTrue(place.left + 275 * place.scale <= 500f)
    }
}
