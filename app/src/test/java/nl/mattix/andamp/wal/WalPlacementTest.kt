// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.menu.menuAnchorBounds
import nl.mattix.andamp.ui.window.WindowScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Where a modern skin's stack lands on the player's surface.
 *
 * The phone is 1080 by 2400 with a 63 px status bar, so the player's surface is 1080 by 2337 at
 * three pixels to a virtual pixel. The band a handle may stay in runs from 8 to 758 virtual pixels
 * down, which puts the box the skin is fitted into at 24 to 2274.
 */
class WalPlacementTest {
    private val screen = WindowScreen(width = 360, height = 779, safeTop = 8, safeBottom = 758)
    private val box = WalPlacement.box(1080, screen, SCALE)

    private fun layout(
        scale: Float,
        left: Float,
        top: Float,
        stackW: Int,
        stackH: Int,
        fit: WalFit = WalPlacement.fit(box, DENSITY, sideways = false),
    ) = WalLayout(fit, scale, left, top, stackW, stackH, windowOpen = false, animating = false, menuTarget = null)

    @Test
    fun `the box is the band a handle stays in, the surface's width across`() {
        assertEquals(IntRect(0, 24, 1080, 2274), box)
        assertEquals(WalFit(1080, 2250, DENSITY, sideways = false), WalPlacement.fit(box, DENSITY, sideways = false))
    }

    @Test
    fun `Winamp Modern's main window at a whole scale sits where the engine put it, below the box's top`() {
        // its main layout is 354 by 280 at least: three pixels to one fit 1062 of the 1080
        val placed = WalPlacement.of(box, layout(3f, left = 9f, top = 0f, stackW = 354, stackH = 280), SCALE)

        assertEquals(IntRect(9, 24, 9 + 1062, 24 + 840), placed.stack)
        assertEquals(IntRect(3, 8, 357, 288), placed.window)
        assertEquals(IntOffset(100, 50), placed.toSkin(Offset(300f, 150f)))
        assertEquals(IntOffset(100, 50), placed.toSkin(Offset(302.9f, 152.9f)))
    }

    @Test
    fun `below twice the size the scale is fractional, and the stack covers every pixel it draws`() {
        val placed = WalPlacement.of(box, layout(1.7f, left = 31f, top = 0f, stackW = 600, stackH = 250), SCALE)

        assertEquals(IntRect(31, 24, 31 + 1020, 24 + 425), placed.stack)
        assertEquals(IntOffset(100, 100), placed.toSkin(Offset(170.5f, 170.5f)))
        assertEquals(
            "a point a hair short of the next skin pixel is still in this one",
            IntOffset(99, 99),
            placed.toSkin(Offset(169.9f, 169.9f)),
        )
    }

    @Test
    fun `the window rect is rounded outward, to the smallest that holds the stack`() {
        val placed = WalPlacement.of(box, layout(1.7f, left = 31f, top = 1f, stackW = 600, stackH = 250), SCALE)
        val stack = placed.stack
        val window = placed.window

        // 31 / 3 and 1051 / 3 fall inside virtual pixels; rounding would cut the right edge off
        assertEquals(IntRect(10, 8, 351, 150), window)
        assertTrue(window.left * SCALE <= stack.left && stack.left < (window.left + 1) * SCALE)
        assertTrue(window.top * SCALE <= stack.top && stack.top < (window.top + 1) * SCALE)
        assertTrue(window.right * SCALE >= stack.right && stack.right > (window.right - 1) * SCALE)
        assertTrue(window.bottom * SCALE >= stack.bottom && stack.bottom > (window.bottom - 1) * SCALE)
    }

    @Test
    fun `sideways the stack the engine centered sits in the middle of the box both ways`() {
        val sideways = WindowScreen(width = 779, height = 344, safeTop = 0, safeBottom = 344)
        val wide = WalPlacement.box(2337, sideways, SCALE)
        val fit = WalPlacement.fit(wide, DENSITY, sideways = true)
        // the engine fits the stack both ways at a whole scale and centers it, rounded down
        val scale = 3f
        val left = kotlin.math.floor((wide.width - 354 * scale) / 2f)
        val top = kotlin.math.floor((wide.height - 280 * scale) / 2f)

        val placed = WalPlacement.of(wide, layout(scale, left, top, stackW = 354, stackH = 280, fit = fit), SCALE)

        assertEquals(
            wide.center.x.toFloat(),
            placed.stack.center.x
                .toFloat(),
            1f,
        )
        assertEquals(
            wide.center.y.toFloat(),
            placed.stack.center.y
                .toFloat(),
            1f,
        )
        assertTrue(wide.contains(placed.stack.topLeft) && placed.stack.right <= wide.right && placed.stack.bottom <= wide.bottom)
    }

    @Test
    fun `a press made into an anchor comes back from the menu's anchor math at the press point`() {
        val placed = WalPlacement.of(box, layout(1.7f, left = 31f, top = 1f, stackW = 600, stackH = 250), SCALE)

        assertPopsAtPress(placed, published = placed.window)
    }

    @Test
    fun `a press comes back at the press point while the published rect still waits for a sliding stack`() {
        val before = WalPlacement.of(box, layout(1.7f, left = 31f, top = 40f, stackW = 600, stackH = 250), SCALE)
        // the stack has grown and moved up since that rect was published
        val placed = WalPlacement.of(box, layout(1.7f, left = 31f, top = 1f, stackW = 600, stackH = 275), SCALE)

        assertPopsAtPress(placed, published = before.window)
        assertPopsAtPress(placed, published = null)
    }

    /** Presses across [placed]'s stack each pop a menu at the press, with [published] in `windowRects[MAIN]`. */
    private fun assertPopsAtPress(
        placed: WalPlacement,
        published: IntRect?,
    ) {
        val rects = mapOf(WindowStore.MAIN to (published ?: placed.window))
        for (at in listOf(Offset(0f, 0f), Offset(1f, 2f), Offset(500.4f, 211.7f), Offset(1019f, 424f))) {
            val anchor = placed.anchorAt(placed.pointAt(at), published)
            val bounds = menuAnchorBounds(anchor, SCALE, 1080, 2337, rects, shrink = 1f)
            val pressX = placed.stack.left + at.x
            val pressY = placed.stack.top + at.y

            assertEquals(WindowStore.MAIN, anchor.window)
            // the nearest virtual pixel: no further than half of one
            assertTrue("x for $at: ${bounds.left} against $pressX", abs(bounds.left - pressX) <= SCALE / 2f)
            assertTrue("y for $at: ${bounds.top} against $pressY", abs(bounds.top - pressY) <= SCALE / 2f)
        }
    }

    private companion object {
        const val SCALE = 3
        const val DENSITY = 2.625f
    }
}
