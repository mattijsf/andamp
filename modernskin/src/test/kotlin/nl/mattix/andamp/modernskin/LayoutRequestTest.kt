// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The windows are laid out again only when something moves or resizes, not for a fade or a value set again. */
class LayoutRequestTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<layer id="glow" x="10" y="10" w="20" h="20"/></layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = 0

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private var t = 0L
    private val rt = SkinRuntime(files, host, FakePlayer()).apply { clock = { t } }

    private fun frames(n: Int) =
        repeat(n) {
            t += 16
            rt.tick()
            rt.drawWindows()
        }

    @Test
    fun `a fade repaints without a layout, a move lays out`() {
        rt.load()
        frames(2)
        val glow = rt.mainLayout!!.findObject("glow")!!
        val before = rt.layoutPasses

        glow.makiCall("settargeta", listOf(Value.Int(0)))
        glow.makiCall("settargetspeed", listOf(Value.Num(0.2)))
        glow.makiCall("gototarget", emptyList())
        frames(20)
        assertEquals("the fade reached its end", 0, glow.alpha)
        assertEquals("no layout for a fade", before, rt.layoutPasses)

        glow.makiCall("settargetx", listOf(Value.Int(40)))
        glow.makiCall("settargetspeed", listOf(Value.Num(0.2)))
        glow.makiCall("gototarget", emptyList())
        frames(20)
        assertTrue("a move lays out", rt.layoutPasses > before)
    }

    @Test
    fun `setting a position again does not lay out, a new one does`() {
        rt.load()
        frames(2)
        val glow = rt.mainLayout!!.findObject("glow")!!
        val before = rt.layoutPasses

        glow.makiCall("setxmlparam", listOf(Value.Str("x"), Value.Str("10")))
        frames(2)
        assertEquals("the same x: no layout", before, rt.layoutPasses)

        glow.makiCall("setxmlparam", listOf(Value.Str("x"), Value.Str("12")))
        frames(2)
        assertEquals("a new x: one layout", before + 1, rt.layoutPasses)
        assertEquals(12, glow.rect.left)
    }
}
