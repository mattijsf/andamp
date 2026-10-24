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
}
