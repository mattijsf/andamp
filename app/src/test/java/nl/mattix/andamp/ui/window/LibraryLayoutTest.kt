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
        assertEquals(0, layout.rowAt((frame.titleH + layout.rowsTop - 1).toFloat(), frame))
        assertEquals(0, layout.rowAt(0f, frame))
    }

    @Test
    fun `rows map back from their own pixels`() {
        val layout = LibraryLayout(12)
        val top = frame.titleH + layout.rowsTop
        assertEquals(0, layout.rowAt(top.toFloat(), frame))
        assertEquals(0, layout.rowAt((top + LibraryLayout.ROW_H - 1).toFloat(), frame))
        assertEquals(1, layout.rowAt((top + LibraryLayout.ROW_H).toFloat(), frame))
        assertEquals(5, layout.rowAt((top + 5 * LibraryLayout.ROW_H).toFloat(), frame))
    }

    @Test
    fun `the window height is chrome plus furniture plus the rows`() {
        val layout = LibraryLayout(12)
        assertEquals(LibraryLayout.furnitureH(LibraryLayout.ROW_H) + 12 * LibraryLayout.ROW_H, layout.contentH)
        assertEquals(frame.chromeH + layout.contentH, layout.height(frame))
    }

    @Test
    fun `available height fills with rows and never goes below the minimum`() {
        // a 800-virtual-px screen: everything left after chrome and furniture
        val tall = LibraryLayout.forAvailableHeight(800, frame)
        assertEquals(
            (800 - frame.chromeH - LibraryLayout.furnitureH(LibraryLayout.ROW_H)) / LibraryLayout.ROW_H,
            tall.visibleRows,
        )

        val cramped = LibraryLayout.forAvailableHeight(50, frame)
        assertEquals(LibraryLayout.MIN_ROWS, cramped.visibleRows)
    }

    @Test
    fun `a library that was never resized opens three fifths as tall as the screen`() {
        for (rowH in listOf(13, 19, 25)) {
            val rows = LibraryLayout.defaultRows(800, frame, rowH)
            val height = LibraryLayout(rows, rowH = rowH).height(frame)

            assertTrue("$rowH px rows: $height", height <= 480)
            assertTrue("$rowH px rows: $height", height > 480 - rowH)
        }
        // a short screen still gets the smallest window
        assertEquals(LibraryLayout.MIN_ROWS, LibraryLayout.defaultRows(100, frame))
    }

    @Test
    fun `a window held at a height between two rows is exactly that tall, the rest left under the last row`() {
        val whole = LibraryLayout(12).height(frame)

        val held = LibraryLayout.filling(whole + 7, LibraryLayout.WIDTH, frame)

        assertEquals(12, held.visibleRows)
        assertEquals(7, held.slack)
        assertEquals(whole + 7, held.height(frame))
        assertEquals("the bar is under the slack", LibraryLayout(12).barTop + 7, held.barTop)
    }

    @Test
    fun `a window held at a whole number of rows has no slack`() {
        val held = LibraryLayout.filling(LibraryLayout(12).height(frame), LibraryLayout.WIDTH, frame)

        assertEquals(12, held.visibleRows)
        assertEquals(0, held.slack)
    }

    @Test
    fun `the bar sits directly under the rows and their rule`() {
        val layout = LibraryLayout(10)
        assertEquals(layout.rowsTop + 10 * LibraryLayout.ROW_H, layout.rowsBottom)
        assertEquals(layout.rowsBottom + LibraryLayout.RULE_H, layout.barTop)
    }

    @Test
    fun `the rows start under the tab strip, its rule, the header and its rule`() {
        val layout = LibraryLayout(rowsFixture)
        assertEquals(layout.barH + LibraryLayout.RULE_H, layout.headerTop)
        assertEquals(layout.headerTop + layout.barH + LibraryLayout.RULE_H, layout.rowsTop)
        // Winamp's own: 13 px parts, the rows 28 px down
        assertEquals(13, layout.barH)
        assertEquals(28, layout.rowsTop)
    }

    @Test
    fun `taller rows make the strip, the header and the bar as tall as a row`() {
        for (rowH in listOf(19, 25)) {
            val layout = LibraryLayout(rowsFixture, rowH = rowH)
            assertEquals(rowH, layout.barH)
            assertEquals(2 * rowH + 2 * LibraryLayout.RULE_H, layout.rowsTop)
            assertEquals(layout.rowsTop + rowsFixture * rowH + LibraryLayout.RULE_H, layout.barTop)
            assertEquals(layout.barTop + rowH, layout.contentH)
        }
    }

    @Test
    fun `the bar's cells are counted from its right edge`() {
        val cells = LibraryLayout.BarCells(262)

        assertEquals(262 - LibraryLayout.ADD_CELL_W, cells.addLeft)
        assertEquals(cells.addLeft - LibraryLayout.PLAY_CELL_W, cells.playLeft)
        assertEquals(cells.playLeft - LibraryLayout.NEW_CELL_W, cells.newLeft)
        assertEquals(cells.playLeft - LibraryLayout.DELETE_CELL_W, cells.deleteLeft)
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

    @Test
    fun `taller rows map back from their own pixels, and make the rows' area taller`() {
        val layout = LibraryLayout(6, rowH = 25)
        val top = frame.titleH + layout.rowsTop

        assertEquals(0, layout.rowAt((top + 24).toFloat(), frame))
        assertEquals(1, layout.rowAt((top + 25).toFloat(), frame))
        assertEquals(6 * 25, layout.rowsH)
        assertEquals(LibraryLayout.furnitureH(25) + 6 * 25, layout.contentH)
    }

    @Test
    fun `the smallest window is no taller with taller rows`() {
        val classic = LibraryLayout(LibraryLayout.MIN_ROWS).height(frame)

        assertEquals(LibraryLayout.MIN_ROWS, LibraryLayout.minRows(LibraryLayout.ROW_H))
        for (rowH in listOf(19, 25)) {
            val smallest = LibraryLayout(LibraryLayout.minRows(rowH), rowH = rowH).height(frame)
            assertTrue("$rowH px rows: $smallest <= $classic", smallest <= classic)
            // held at the classic minimum, it fits and the rest is slack
            val held = LibraryLayout.filling(classic, LibraryLayout.WIDTH, frame, rowH)
            assertEquals(classic, held.height(frame))
            assertEquals(rowH, held.rowH)
        }
    }

    @Test
    fun `available height fills with as many taller rows as fit`() {
        val tall = LibraryLayout.forAvailableHeight(800, frame, rowH = 25)

        assertEquals((800 - frame.chromeH - LibraryLayout.furnitureH(25)) / 25, tall.visibleRows)
        assertTrue(tall.height(frame) <= 800)
    }
}
