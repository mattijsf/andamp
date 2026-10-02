// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a help popup lands. The window is edge to edge, so its y = 0 is behind
 * the status bar.
 */
class AboveAnchorTest {
    private val window = IntSize(1080, 2400)
    private val popup = IntSize(600, 300)

    private fun at(
        anchor: IntRect,
        content: IntSize = popup,
        size: IntSize = window,
    ) = AboveAnchor.calculatePosition(anchor, size, LayoutDirection.Ltr, content)

    /** A mark of [MARK] pixels with its top-left at ([x], [y]). */
    private fun mark(
        x: Int,
        y: Int,
    ) = IntRect(x, y, x + MARK, y + MARK)

    @Test
    fun `with room above, it sits over the mark and centered on it`() {
        val anchor = mark(500, 1200)

        val at = at(anchor)

        assertEquals("the bottom of the popup meets the top of the mark", 1200 - 300, at.y)
        assertEquals("the popup is centered on the mark", anchor.center.x - 300, at.x)
    }

    @Test
    fun `a mark near the top gets its popup underneath`() {
        // 300 tall over a mark at y = 320 would start at 20, inside the status bar
        val anchor = mark(500, 320)

        val at = at(anchor)

        assertEquals("the popup sits under the mark", anchor.bottom, at.y)
        assertTrue("the popup is clear of the top edge: ${at.y}", at.y >= AboveAnchor.MARGIN)
    }

    @Test
    fun `a mark near the bottom keeps its popup against the mark`() {
        val anchor = mark(500, 2380)

        val at = at(anchor)

        assertEquals("the popup's bottom edge meets the mark's top", anchor.top, at.y + 300)
        assertTrue("the popup is on screen: ${at.y}", at.y >= 0)
    }

    @Test
    fun `a window shorter than the one the anchor was measured in still sits on the mark`() {
        // windowSize is the visible display frame and anchorBounds is
        // window-relative; they differ by the insets
        val anchor = mark(500, 2000)

        val at = at(anchor, size = IntSize(1080, 2100))

        assertEquals(anchor.top, at.y + 300)
    }

    @Test
    fun `a mark at either side keeps the popup a margin from the edge`() {
        assertEquals("the popup is a margin from the left edge", AboveAnchor.MARGIN, at(mark(0, 1200)).x)
        assertEquals(
            "the popup is a margin from the right edge",
            window.width - popup.width - AboveAnchor.MARGIN,
            at(mark(1070, 1200)).x,
        )
    }

    @Test
    fun `a popup taller than the window is placed at the margin`() {
        val tall = IntSize(600, 3000)

        val at = at(mark(500, 1200), content = tall)

        assertEquals(AboveAnchor.MARGIN, at.y)
    }

    @Test
    fun `a popup wider than the window starts at the margin`() {
        val wide = IntSize(2000, 300)

        assertEquals(AboveAnchor.MARGIN, at(mark(500, 1200), content = wide).x)
    }

    private companion object {
        const val MARK = 40
    }
}
