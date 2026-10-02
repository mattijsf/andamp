// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Test

class OscColorIndexTest {
    @Test
    fun `matches webamp's colorIndex table`() {
        val expected =
            mapOf(
                0 to 3,
                1 to 3,
                2 to 2,
                3 to 2,
                4 to 1,
                5 to 1,
                6 to 0,
                7 to 0,
                8 to 1,
                9 to 1,
                10 to 2,
                11 to 2,
                12 to 3,
                13 to 3,
                14 to 4,
                15 to 4,
            )
        for ((y, idx) in expected) {
            assertEquals("row $y has webamp's color index", idx, oscColorIndex(y))
        }
    }
}
