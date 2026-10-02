// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapFontTest {
    @Test
    fun `sprite rects follow the 5x6 grid from webamp FONT_LOOKUP`() {
        assertEquals(Sprite(0, 0, 5, 6), BitmapFont.sprite('a')) // row 0 col 0
        assertEquals(Sprite(125, 0, 5, 6), BitmapFont.sprite('z')) // row 0 col 25
        assertEquals(Sprite(0, 6, 5, 6), BitmapFont.sprite('0')) // row 1 col 0
        assertEquals(Sprite(150, 6, 5, 6), BitmapFont.sprite('#')) // row 1 col 30
        assertEquals(Sprite(20, 12, 5, 6), BitmapFont.sprite('*')) // row 2 col 4
        assertEquals(Sprite(150, 0, 5, 6), BitmapFont.sprite(' ')) // row 0 col 30
    }

    @Test
    fun `angle brackets and braces alias to the bracket cells`() {
        assertEquals(BitmapFont.sprite('['), BitmapFont.sprite('<'))
        assertEquals(BitmapFont.sprite('['), BitmapFont.sprite('{'))
        assertEquals(BitmapFont.sprite(']'), BitmapFont.sprite('>'))
        assertEquals(BitmapFont.sprite(']'), BitmapFont.sprite('}'))
    }

    @Test
    fun `normalize lowercases and strips accents`() {
        assertEquals("cafe", BitmapFont.normalize("Café"))
        assertEquals("uber", BitmapFont.normalize("Über"))
    }

    @Test
    fun `normalize keeps the nordic glyphs that exist in the sheet`() {
        assertEquals("ÅÄÖ", BitmapFont.normalize("åäö"))
        assertEquals("ÅÄÖ", BitmapFont.normalize("ÅÄÖ"))
    }

    @Test
    fun `unknown characters become spaces`() {
        assertEquals(" ", BitmapFont.normalize("→"))
        assertEquals(BitmapFont.sprite(' '), BitmapFont.sprite('~'))
    }
}
