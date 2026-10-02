// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PleditTxtTest {
    @Test
    fun `parses the base skin file`() {
        val style =
            PleditTxt.parse(
                """
                [Text]
                Normal=#00FF00
                Current=#FFFFFF
                NormalBG=#000000
                SelectedBG=#0000C6
                Font=Arial
                """.trimIndent(),
            )
        assertEquals(Color(0xFF00FF00), style.normal)
        assertEquals(Color(0xFFFFFFFF), style.current)
        assertEquals(Color(0xFF000000), style.normalBg)
        assertEquals(Color(0xFF0000C6), style.selectedBg)
        assertEquals("Arial", style.fontName)
    }

    @Test
    fun `section and keys are case-insensitive`() {
        val style = PleditTxt.parse("[TEXT]\nNORMAL=#FF0000\nfont=Verdana")
        assertEquals(Color(0xFFFF0000), style.normal)
        assertEquals("Verdana", style.fontName)
    }

    @Test
    fun `hash prefix is optional and overlong colors truncate to six digits`() {
        val style = PleditTxt.parse("[Text]\nNormal=00FF00\nCurrent=#FFFFFF99")
        assertEquals(Color(0xFF00FF00), style.normal)
        assertEquals(Color(0xFFFFFFFF), style.current)
    }

    @Test
    fun `comments quotes and a second equals sign are ignored`() {
        val style =
            PleditTxt.parse(
                """
                [Text]
                Normal=#123456 ; trailing comment
                Font="Tahoma"=Bold
                """.trimIndent(),
            )
        assertEquals(Color(0xFF123456), style.normal)
        assertEquals("Tahoma", style.fontName)
    }

    @Test
    fun `null or garbage input falls back to Winamp defaults`() {
        assertEquals(PleditStyle.DEFAULT, PleditTxt.parse(null))
        assertEquals(PleditStyle.DEFAULT, PleditTxt.parse("not an ini at all"))
        // invalid color value falls back per-key, valid keys still parse
        val style = PleditTxt.parse("[Text]\nNormal=zzz\nFont=Courier")
        assertEquals(PleditStyle.DEFAULT.normal, style.normal)
        assertEquals("Courier", style.fontName)
    }

    @Test
    fun `keys outside the Text section are ignored`() {
        val style = PleditTxt.parse("[Other]\nNormal=#FF0000")
        assertEquals(PleditStyle.DEFAULT.normal, style.normal)
    }
}
