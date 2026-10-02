// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private const val BG = 0xFF00FF00.toInt() // the separator color, sampled at x=0
private const val INK = 0xFF000000.toInt()

class GenTitleFontTest {
    /**
     * Builds a fake GEN.BMP where every letter is [letterWidth] wide, laid out
     * as Winamp does: separator column, letter, separator, letter...
     */
    private fun sheet(
        letterWidth: Int,
        rows: List<Int> = listOf(88, 96),
    ): (Int, Int) -> Int =
        { x, y ->
            when {
                y !in rows -> BG

                x == 0 -> BG

                // letters occupy [1 + i*(w+1), +w), separators sit between them
                ((x - 1) % (letterWidth + 1)) < letterWidth -> INK

                else -> BG
            }
        }

    @Test
    fun `scans all 26 letters from both rows`() {
        val font = GenTitleFont.scan(1 + 26 * 4, 110, sheet(letterWidth = 3))
        assertNotNull(font)
        for (letter in 'A'..'Z') {
            assertNotNull("the focused row has $letter", font!!.sprite(letter, focused = true))
            assertNotNull("the unfocused row has $letter", font.sprite(letter, focused = false))
        }
    }

    @Test
    fun `letters land at the right coordinates and row`() {
        val font = GenTitleFont.scan(1 + 26 * 4, 110, sheet(letterWidth = 3))!!
        val a = font.sprite('A', focused = true)!!
        assertEquals(1, a.x)
        assertEquals(88, a.y)
        assertEquals(3, a.w)
        assertEquals(7, a.h)
        val b = font.sprite('B', focused = true)!!
        assertEquals(5, b.x) // 1 + 3 + 1 separator
        assertEquals(96, font.sprite('A', focused = false)!!.y)
    }

    @Test
    fun `lookup is case insensitive`() {
        val font = GenTitleFont.scan(1 + 26 * 4, 110, sheet(letterWidth = 3))!!
        assertEquals(font.sprite('M', focused = true), font.sprite('m', focused = true))
    }

    @Test
    fun `width sums the glyphs and counts spaces`() {
        val font = GenTitleFont.scan(1 + 26 * 4, 110, sheet(letterWidth = 3))!!
        assertEquals(6, font.width("AB", focused = true))
        assertEquals(6 + GenTitleFont.SPACE_W, font.width("A B", focused = true))
    }

    @Test
    fun `a sheet too short for the letter rows yields no font`() {
        assertNull(GenTitleFont.scan(200, 90, sheet(letterWidth = 3)))
    }

    @Test
    fun `a row that runs out of letters yields no font`() {
        // only room for a few letters before the sheet ends
        assertNull(GenTitleFont.scan(12, 110, sheet(letterWidth = 3)))
    }
}
