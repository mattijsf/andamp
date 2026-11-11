// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The skin browser's geometry. */
class SkinManagerLayoutTest {
    private val frame = GenFrame

    @Test
    fun `taller rows make the list taller and map back from their own pixels`() {
        val layout = SkinManagerLayout(4, rowH = 25)

        assertEquals(SkinManagerLayout(4).listHeight() + 4 * (25 - SkinManagerLayout.ROW_H), layout.listHeight())
        assertEquals(layout.rowY(0) + 2 * 25, layout.rowY(2))
        assertEquals(0, layout.rowAt((frame.titleH + layout.rowY(1) - 1).toFloat(), frame.titleH))
        assertEquals(1, layout.rowAt((frame.titleH + layout.rowY(1)).toFloat(), frame.titleH))
    }

    @Test
    fun `the smallest window is no taller with taller rows`() {
        val classic = SkinManagerLayout(SkinManagerLayout.MIN_ROWS).height(frame)

        assertEquals(SkinManagerLayout.MIN_ROWS, SkinManagerLayout.minRows(SkinManagerLayout.ROW_H))
        for (rowH in listOf(19, 25)) {
            val rows = SkinManagerLayout.minRows(rowH)
            assertTrue("at least one row: $rows", rows >= 1)
            assertTrue(SkinManagerLayout(rows, rowH = rowH).height(frame) <= classic)
        }
    }
}
