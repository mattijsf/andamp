// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.FontFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Which face a text is drawn and measured with: the skin's file, a stand-in, or the default. */
class FontFaceTest {
    @Test
    fun `a font the skin brings is its own file, made bold by the renderer`() {
        val font = FontDef("lucida", "fonts/lucida.ttf")
        assertEquals(FontFace.SkinFile(font, fakeBold = false), FontFace.of(font, bold = false))
        assertEquals(FontFace.SkinFile(font, fakeBold = true), FontFace.of(font, bold = true))
    }

    @Test
    fun `Tahoma and Arial without a file get their stand-ins, bold from a bold cut`() {
        assertEquals(FontFace.StandIn("DejaVuSansCondensed", bold = false), FontFace.of(FontDef("tahoma", ""), false))
        assertEquals(FontFace.StandIn("LiberationSans", bold = true), FontFace.of(FontDef("arial", ""), true))
        assertEquals("fonts/LiberationSans-Bold.ttf", FontFace.StandIn("LiberationSans", bold = true).file)
    }

    @Test
    fun `a stand-in is only for a font named without a file`() {
        val brought = FontDef("arial", "fonts/arial.ttf")
        assertEquals(FontFace.SkinFile(brought, fakeBold = false), FontFace.of(brought, false))
        val unknown = FontDef("verdana", "")
        assertEquals(FontFace.SkinFile(unknown, fakeBold = true), FontFace.of(unknown, true))
    }

    @Test
    fun `no font is the default, made bold by the renderer`() {
        assertEquals(FontFace.Default(fakeBold = true), FontFace.of(null, true))
    }

    @Test
    fun `every stand-in's file is in Andamp's default set`() {
        for (family in FontFace.STAND_INS.values) {
            for (bold in listOf(false, true)) {
                val file = FontFace.StandIn(family, bold).file
                assertNotNull(file, AndampDefaultSet.read(file))
            }
        }
    }
}
