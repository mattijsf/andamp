// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class VisColorTxtTest {
    @Test
    fun `parses lines with comment tails like the base skin`() {
        val colors = VisColorTxt.parse("0,0,0, // color 0 = black\n24,33,41, // dots")
        assertEquals(Color(0, 0, 0), colors[0])
        assertEquals(Color(24, 33, 41), colors[1])
    }

    @Test
    fun `commas are optional and whitespace is tolerated`() {
        val colors = VisColorTxt.parse("  10 20 30\n1,2,3")
        assertEquals(Color(10, 20, 30), colors[0])
        assertEquals(Color(1, 2, 3), colors[1])
    }

    @Test
    fun `returns 24 entries with per-index fallback`() {
        val colors = VisColorTxt.parse("5,5,5")
        assertEquals(24, colors.size)
        assertEquals(Color(5, 5, 5), colors[0])
        assertEquals(VisColorTxt.DEFAULT[1], colors[1])
        assertEquals(VisColorTxt.DEFAULT[23], colors[23])
    }

    @Test
    fun `null input returns the fallback list`() {
        assertEquals(VisColorTxt.DEFAULT, VisColorTxt.parse(null))
    }

    @Test
    fun `out-of-range components clamp to 255`() {
        val colors = VisColorTxt.parse("999,0,0")
        assertEquals(Color(255, 0, 0), colors[0])
    }
}
