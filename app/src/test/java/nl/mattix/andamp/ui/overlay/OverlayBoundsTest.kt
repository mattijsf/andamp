// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.overlay

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.ui.window.WindowScreen
import nl.mattix.andamp.wal.WalLayout
import nl.mattix.andamp.wal.WalPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBoundsTest {
    private val main = IntRect(0, 0, 275, 116)
    private val eq = IntRect(0, 116, 275, 232)

    @Test
    fun `one window makes a window its own size, where it is on screen`() {
        val at = OverlayBounds.windowFor(listOf(main), scale = 3, originX = 127, originY = 60)

        assertEquals(IntRect(127, 60, 127 + 825, 60 + 348), at)
    }

    @Test
    fun `two windows make one that holds both`() {
        val at = OverlayBounds.windowFor(listOf(main, eq), scale = 3, originX = 127, originY = 60)!!

        assertEquals(60, at.top)
        assertEquals(60 + 232 * 3, at.bottom)
    }

    @Test
    fun `a window off to one side widens it that way`() {
        val moved = IntRect(40, 300, 315, 416)

        val at = OverlayBounds.windowFor(listOf(main, moved), scale = 2, originX = 0, originY = 0)!!

        assertEquals(0, at.left)
        assertEquals(315 * 2, at.right)
        assertEquals(416 * 2, at.bottom)
    }

    @Test
    fun `nothing drawn needs no window at all`() {
        assertNull(OverlayBounds.windowFor(emptyList(), scale = 3, originX = 0, originY = 0))
        assertNull(OverlayBounds.windowFor(listOf(IntRect(10, 10, 10, 10)), scale = 3, originX = 0, originY = 0))
    }

    @Test
    fun `the padding is on every side`() {
        val at = OverlayBounds.windowFor(listOf(main), scale = 1, originX = 0, originY = 0, pad = 4)!!

        assertEquals(IntRect(-4, -4, 279, 120), at)
    }

    @Test
    fun `a modern skin's rect at a fractional scale takes every pixel it draws`() {
        // the floating player's surface starts below a 63 px status bar, three pixels to a virtual one
        val box = WalPlacement.box(1080, WindowScreen(width = 360, height = 779, safeTop = 0, safeBottom = 763), scale = 3)
        val fit = WalPlacement.fit(box, density = 2.625f, sideways = false)
        val layout = WalLayout(fit, 1.7f, 31f, 1f, 600, 250, windowOpen = false, animating = false, menuTarget = null)
        val placed = WalPlacement.of(box, layout, scale = 3)

        val at = OverlayBounds.windowFor(listOf(placed.window), scale = 3, originX = 0, originY = 63)!!

        val drawn = IntRect(placed.stack.left, 63 + placed.stack.top, placed.stack.right, 63 + placed.stack.bottom)
        assertTrue(
            "$at holds $drawn",
            at.left <= drawn.left && at.top <= drawn.top && at.right >= drawn.right && at.bottom >= drawn.bottom,
        )
    }
}
