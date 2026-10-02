// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library window's geometry. `rowAt` clamps, because a touch above the first row
 * would compute row -1, which once scrolled acts on the row above the viewport.
 */
class LibraryLayoutTest {
    private val rowsFixture = 10

    private val frame = GenFrame

    @Test
    fun `a touch in the header area clamps to row zero`() {
        val layout = LibraryLayout(12)
        assertEquals(0, layout.rowAt((frame.titleH + LibraryLayout.ROWS_TOP - 1).toFloat(), frame))
        assertEquals(0, layout.rowAt(0f, frame))
    }

    @Test
    fun `rows map back from their own pixels`() {
        val layout = LibraryLayout(12)
        val top = frame.titleH + LibraryLayout.ROWS_TOP
        assertEquals(0, layout.rowAt(top.toFloat(), frame))
        assertEquals(0, layout.rowAt((top + LibraryLayout.ROW_H - 1).toFloat(), frame))
        assertEquals(1, layout.rowAt((top + LibraryLayout.ROW_H).toFloat(), frame))
        assertEquals(5, layout.rowAt((top + 5 * LibraryLayout.ROW_H).toFloat(), frame))
    }

    @Test
    fun `the window height is chrome plus furniture plus the rows`() {
        val layout = LibraryLayout(12)
        assertEquals(LibraryLayout.FURNITURE_H + 12 * LibraryLayout.ROW_H, layout.contentH)
        assertEquals(frame.chromeH + layout.contentH, layout.height(frame))
    }

    @Test
    fun `available height fills with rows and never goes below the minimum`() {
        // a 800-virtual-px screen: everything left after chrome and furniture
        val tall = LibraryLayout.forAvailableHeight(800, frame)
        assertEquals((800 - frame.chromeH - LibraryLayout.FURNITURE_H) / LibraryLayout.ROW_H, tall.visibleRows)

        val cramped = LibraryLayout.forAvailableHeight(50, frame)
        assertEquals(LibraryLayout.MIN_ROWS, cramped.visibleRows)
    }

    @Test
    fun `the bar sits directly under the rows and their rule`() {
        val layout = LibraryLayout(10)
        assertEquals(LibraryLayout.ROWS_TOP + 10 * LibraryLayout.ROW_H, layout.rowsBottom)
        assertEquals(layout.rowsBottom + LibraryLayout.RULE_H, layout.barTop)
    }

    @Test
    fun `the rows start under the tab strip, its rule, the header and its rule`() {
        assertEquals(LibraryLayout.BAR_H + LibraryLayout.RULE_H, LibraryLayout.HEADER_TOP)
        assertEquals(
            LibraryLayout.HEADER_TOP + LibraryLayout.BAR_H + LibraryLayout.RULE_H,
            LibraryLayout.ROWS_TOP,
        )
    }

    @Test
    fun `tab cells span the whole strip for both frames and any count`() {
        for (frame in listOf<WindowFrame>(GenFrame, PleditWindowFrame)) {
            val listW = LibraryLayout(rowsFixture).listWidth(frame)
            for (n in listOf(3, 4)) {
                val cells = LibraryLayout.tabCells(listW, n)
                assertEquals(n, cells.size)
                assertEquals(0, cells.first().start)
                assertEquals(listW, cells.last().end)
                cells.zipWithNext { a, b -> assertEquals(a.end, b.start) }
                // never a squeezed cell: the remainder lands on the last one
                assertTrue(cells.all { it.end - it.start >= listW / n })
            }
        }
    }

    @Test
    fun `a width step is the 25px one every list window tiles in`() {
        assertEquals(LibraryLayout.WIDTH, LibraryLayout.widthOfCols(0))
        assertEquals(LibraryLayout.WIDTH + 25, LibraryLayout.widthOfCols(1))
        assertEquals(LibraryLayout.WIDTH + 75, LibraryLayout.widthOfCols(3))
    }

    @Test
    fun `a widened window hands every extra pixel to its list`() {
        val frame: WindowFrame = GenFrame
        val narrow = LibraryLayout(rowsFixture)
        val wide = LibraryLayout(rowsFixture, LibraryLayout.widthOfCols(2))

        assertEquals(narrow.listWidth(frame) + 50, wide.listWidth(frame))
        assertEquals(wide.listWidth(frame) - LibraryLayout.SCROLLBAR_W, wide.rowsWidth(frame))
        assertEquals(2, wide.cols)
    }

    @Test
    fun `the tab strip still spans a widened window end to end`() {
        val wide = LibraryLayout(rowsFixture, LibraryLayout.widthOfCols(3))
        val listW = wide.listWidth(GenFrame)

        val cells = LibraryLayout.tabCells(listW, 4)

        assertEquals(0, cells.first().start)
        assertEquals(listW, cells.last().end)
    }
}
