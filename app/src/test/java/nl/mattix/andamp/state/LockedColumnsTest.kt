// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Test

class LockedColumnsTest {
    @Test
    fun `the columns survive a trip through the store's text`() {
        val columns = listOf(listOf(WindowStore.MAIN, WindowStore.PLAYLIST), listOf(WindowStore.EQ, WindowStore.MILKDROP))

        assertEquals("main,pl|eq,milkdrop", LockedColumns.encode(columns))
        assertEquals(columns, LockedColumns.decode(LockedColumns.encode(columns)))
    }

    @Test
    fun `a window named twice, one unknown and one missing are put right`() {
        val made = LockedColumns.normalize(listOf(listOf("pl", "video", "pl", "main")), sideways = false)

        assertEquals(listOf(listOf("pl", "main", "eq", "milkdrop")), made)
    }

    @Test
    fun `turned sideways, one column becomes two, and upright two become one`() {
        assertEquals(
            listOf(listOf("main", "eq", "milkdrop", "pl"), emptyList()),
            LockedColumns.normalize(listOf(listOf("main", "eq", "milkdrop", "pl")), sideways = true),
        )
        assertEquals(
            listOf(listOf("main", "eq", "milkdrop", "pl")),
            LockedColumns.normalize(listOf(listOf("main", "eq", "milkdrop"), listOf("pl")), sideways = false),
        )
    }
}
