// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A frame is asked for only when something shown changed: not for a status picture or a time that did not move. */
class RedrawRequestTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<status id="state" x="0" y="0" w="10" h="10"/>""" +
                        """<text id="clock" display="time" x="20" y="0" w="60" h="12"/>""" +
                        """</layout></container>""",
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
            ) = text.length * 6

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private var t = 0L
    private val player = FakePlayer().apply { clock = { t } }
    private val rt = SkinRuntime(files, host, player).apply { clock = { t } }

    /** How many of [n] 16 ms frames asked for a redraw. */
    private fun redraws(n: Int): Int {
        var asked = 0
        repeat(n) {
            t += 16
            if (rt.tick()) asked++
            rt.drawWindows()
        }
        return asked
    }

    @Test
    fun `a stopped skin asks for no frames`() {
        rt.load()
        redraws(10)
        assertEquals(0, redraws(120))
    }

    @Test
    fun `playing, the time asks for a frame when its second changes`() {
        rt.load()
        redraws(10)
        player.play()
        val asked = redraws(250) // 4 s
        assertTrue("the start and about one frame a second, not every frame: $asked", asked in 2..8)
    }
}
