// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.overlay

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
