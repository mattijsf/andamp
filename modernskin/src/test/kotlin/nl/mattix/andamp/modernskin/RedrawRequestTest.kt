// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A frame is asked for only when something shown changed: not for a status picture or a time that did not move. */
class RedrawRequestTest {
    private fun files(
        body: String,
        others: String = "",
    ) = object : SkinFiles {
        private val m =
            mapOf(
                "skin.xml" to
                    """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                "player.xml" to
                    """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">$body""" +
                    """</layout></container>$others""",
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

    private fun runtime(
        body: String =
            """<status id="state" x="0" y="0" w="10" h="10"/><text id="clock" display="time" x="20" y="0" w="60" h="12"/>""",
        others: String = "",
    ) = SkinRuntime(files(body, others), host, player).apply {
        clock = { t }
        load()
    }

    private val rt by lazy { runtime() }

    /** How many of [n] 16 ms frames asked for a redraw, on [skin]. */
    private fun redraws(
        n: Int,
        skin: SkinRuntime = rt,
    ): Int {
        var asked = 0
        repeat(n) {
            t += 16
            if (skin.tick()) asked++
            skin.drawWindows()
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

    @Test
    fun `a visualizer in mode 0, hidden, or in a closed window asks for no frame while playing`() {
        val skin =
            runtime(
                """<vis id="off" x="0" y="0" w="72" h="16" mode="0"/>""" +
                    """<vis id="hidden" x="0" y="20" w="72" h="16" visible="0"/>""" +
                    """<group id="folded" x="0" y="40" w="80" h="20" visible="0">""" +
                    """<vis id="inside" x="0" y="0" w="72" h="16"/></group>""",
                """<container id="pl" name="pl" default_visible="0"><layout id="normal" w="200" h="50">""" +
                    """<vis id="docked" x="0" y="0" w="72" h="16"/></layout></container>""",
            )
        val pl = checkNotNull(skin.container("pl"))
        skin.openWindow(pl)
        redraws(5, skin)
        skin.closeWindow(pl)
        redraws(5, skin)
        player.play()

        assertEquals("only the start", 1, redraws(120, skin))
        assertFalse(skin.framesWanted)
    }

    @Test
    fun `a visualizer asks for at most one frame per 25 ms step`() {
        val skin = runtime("""<vis id="vis" x="0" y="0" w="72" h="16"/>""")
        redraws(5, skin)
        player.play()

        // 62 display frames of 16 ms in a second: a step is due on every second one, as the bars
        // move on from the frame that drew them
        val asked = redraws(62, skin)
        assertTrue("at most 40 frames a second, not 62: $asked", asked in 30..40)
    }

    @Test
    fun `a progress grid asks for a frame only when its filled part changes`() {
        player.lengthMs = 100_000
        val skin = runtime("""<progressgrid id="seek" x="0" y="0" w="100" h="8"/>""")
        redraws(5, skin)
        player.play()

        // a pixel a second
        val asked = redraws(250, skin)
        assertTrue("the start and a frame for each pixel over 4 s, not every frame: $asked", asked in 4..6)
    }

    @Test
    fun `an equalizer curve asks for a frame when a band changes while stopped`() {
        val skin = runtime("""<eqvis id="curve" x="0" y="0" w="113" h="19"/>""")
        redraws(5, skin)

        player.setEqBand(3, 200)
        assertEquals(1, redraws(10, skin))
        player.eqPreamp = 40
        assertEquals(1, redraws(10, skin))
    }

    @Test
    fun `after stop the visualizer falls to rest, then asks for nothing`() {
        val skin = runtime("""<vis id="vis" x="0" y="0" w="72" h="16"/>""")
        redraws(5, skin)
        player.play()
        redraws(125, skin)
        player.stop()

        assertTrue("its bars and peaks fall for a while", redraws(125, skin) > 20)
        assertFalse(skin.framesWanted)
        assertEquals("then it rests", 0, redraws(62, skin))
    }

    @Test
    fun `the oscilloscope draws its flat line once after stop`() {
        val skin = runtime("""<vis id="vis" x="0" y="0" w="72" h="16" mode="2"/>""")
        redraws(5, skin)
        player.play()
        redraws(30, skin)
        player.stop()

        assertEquals(1, redraws(62, skin))
        assertFalse(skin.framesWanted)
        skin.mainLayout?.findObject("vis")?.makiCall("setmode", listOf(Value.Int(1)))
        assertEquals("a new mode shows at once", 1, redraws(2, skin))
    }
}
