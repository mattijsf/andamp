// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `a title in letters the font has, accented ones among them, can be spelled`() {
        assertTrue(BitmapFont.covers("1. Björk - Jóga (Live) [2003]"))
        assertTrue(BitmapFont.covers("Sigur Rós – Hoppípolla"))
    }

    @Test
    fun `a title with a letter the font lacks cannot be spelled`() {
        assertFalse(BitmapFont.covers("1. Кино - Группа крови"))
        assertFalse(BitmapFont.covers("東京事変"))
        assertFalse(BitmapFont.covers("Straße"))
    }

    @Test
    fun `a text that fits its room is only folded`() {
        assertEquals("autechre", BitmapFont.fitted("Autechre", 8))
        assertEquals("autechre", BitmapFont.fitted("Autechre", 20))
    }

    @Test
    fun `a text too long for its room ends on a whole letter and the ellipsis`() {
        assertEquals("autec…", BitmapFont.fitted("Autechre", 6))
        // no room at all still shows that something was cut
        assertEquals("…", BitmapFont.fitted("Autechre", 1))
        assertEquals("…", BitmapFont.fitted("Autechre", 0))
    }

    @Test
    fun `every glyph is a cell of the grid, and the space is one of them`() {
        assertTrue(BitmapFont.sprite(' ') in BitmapFont.glyphs)
        assertTrue(BitmapFont.glyphs.all { it.x % BitmapFont.CHAR_W == 0 && it.y % BitmapFont.CHAR_H == 0 })
        assertEquals(BitmapFont.glyphs.size, BitmapFont.glyphs.distinct().size)
    }
}
