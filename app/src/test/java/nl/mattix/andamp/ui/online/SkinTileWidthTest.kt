// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SkinTileWidthTest {
    @Test
    fun `a phone keeps the small tiles, three to a row`() {
        assertEquals(108.dp, tileMinWidth(411.dp))
        assertEquals(3, (411 / tileMinWidth(411.dp).value).toInt())
    }

    @Test
    fun `a tablet or a desktop holds six to a row, and the tiles grow`() {
        assertEquals(6, (1707 / tileMinWidth(1707.dp).value).toInt())
        assertEquals(6, (2048 / tileMinWidth(2048.dp).value).toInt())
    }
}
