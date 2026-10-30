// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine places each text where every renderer draws it ([DrawCmd.Text.x],
 * [DrawCmd.Text.baseline]): across by the width the host measures, in fractions, less the
 * ticker's scroll; up and down by the host's line when the font file gives Windows' none, the
 * centered one on a whole pixel.
 */
class TextPlacementTest {
    private fun skin(texts: String) =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        texts + """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    // 2.25 px a letter, and a line 7.5 up and 2.25 down: fractions on purpose
    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = text.length * 2

            override fun textAdvance(
                text: String,
                font: FontDef?,
                size: Int,
                bold: Boolean,
            ) = text.length * 2.25f

            override fun lineMetrics(
                font: FontDef?,
                size: Int,
                bold: Boolean,
            ) = 7.5f to 2.25f

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private fun texts(xml: String): Map<String, DrawCmd.Text> {
        val rt = SkinRuntime(skin(xml), host, FakePlayer(), clock = { 0L })
        rt.load()
        return rt
            .drawWindows()
            .flatMap { it.second }
            .filterIsInstance<DrawCmd.Text>()
            .associateBy { it.text }
    }

    @Test
    fun `across, a text sits left, centered or right by its measured width`() {
        val t =
            texts(
                """<text id="l" text="Left" x="10" y="0" w="100" h="12" align="left"/>""" +
                    """<text id="c" text="Mid" x="10" y="20" w="100" h="12" align="center"/>""" +
                    """<text id="r" text="Right" x="10" y="40" w="100" h="12" align="right"/>""",
            )
        val left = t.getValue("Left")
        val mid = t.getValue("Mid")
        val right = t.getValue("Right")
        assertEquals(left.rect.left.toFloat(), left.x)
        assertEquals("by the fraction the host measures", mid.rect.left + (mid.rect.width - 3 * 2.25f) / 2f, mid.x)
        assertEquals(right.rect.right - 5 * 2.25f, right.x)
    }

    @Test
    fun `up and down, a text sits on the line the host gives at the top or centered, the centered one on a whole pixel`() {
        val t =
            texts(
                """<text id="t" text="Top" x="0" y="0" w="100" h="13" valign="top"/>""" +
                    """<text id="m" text="Center" x="0" y="20" w="100" h="13" valign="center"/>""",
            )
        val top = t.getValue("Top")
        val center = t.getValue("Center")
        assertEquals(top.rect.top + 7.5f, top.baseline)
        // what is left of the line's 9.75 px, halved, is kept to a whole pixel
        assertEquals(center.rect.top + ((center.rect.height - 9.75f) / 2f).toInt() + 7.5f, center.baseline)
    }

    @Test
    fun `a Tahoma text without a file sits on Windows' line of its stand-in`() {
        val t =
            texts(
                """<elements><truetypefont id="tahoma" file=""/></elements>""" +
                    """<text id="t" text="Tahoma" font="tahoma" fontsize="14" x="0" y="0" w="100" h="13" valign="top"/>""",
            )
        val baseline = t.getValue("Tahoma").baseline
        assertEquals("Windows' ascent is a whole pixel, not the host's 7.5", baseline, Math.round(baseline).toFloat())
    }
}
