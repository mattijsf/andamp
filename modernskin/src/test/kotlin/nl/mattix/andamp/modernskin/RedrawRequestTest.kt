// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A frame is asked for only when something shown changed: not for a status picture or a time that
 * did not move, nor for a text or a strip frame a script sets again.
 */
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

        // 62 display frames of 16 ms in a second; the steps keep to a 25 ms grid, 40 in a second
        val asked = redraws(62, skin)
        assertTrue("40 frames a second, not 62 nor 31: $asked", asked in 38..40)
    }

    @Test
    fun `the oscilloscope asks for a frame per 25 ms step too`() {
        val skin = runtime("""<vis id="vis" x="0" y="0" w="72" h="16" mode="2"/>""")
        redraws(5, skin)
        player.play()

        val asked = redraws(62, skin)
        assertTrue("40 frames a second, not 62 nor 31: $asked", asked in 38..40)
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

    @Test
    fun `a script's new alpha and a fade show on the next frames without a layout pass`() {
        val skin =
            runtime(
                """<group id="glow" x="0" y="0" w="20" h="20">""" +
                    """<rect id="box" x="0" y="0" w="10" h="10" color="255,0,0" filled="1"/></group>""",
            )
        redraws(5, skin)
        val glow = checkNotNull(skin.mainLayout?.findObject("glow"))

        fun drawnAlpha() =
            skin
                .draw()
                .filterIsInstance<DrawCmd.Fill>()
                .single()
                .alpha
        val passes = skin.layoutPasses
        glow.makiCall("setalpha", listOf(Value.Int(128)))
        assertEquals(1, redraws(1, skin))
        assertEquals("the box inside paints at its group's alpha", 128, drawnAlpha())

        glow.makiCall("settargeta", listOf(Value.Int(0)))
        glow.makiCall("settargetspeed", listOf(Value.Num(1.0)))
        glow.makiCall("gototarget", emptyList())
        redraws(31, skin)
        assertTrue("half way: ${drawnAlpha()}", drawnAlpha() in 32..96)
        assertEquals(passes, skin.layoutPasses)
        redraws(40, skin)
        assertTrue("faded out", skin.draw().filterIsInstance<DrawCmd.Fill>().all { it.alpha == 0 })
    }

    @Test
    fun `a script writing the text a text shows again asks for no frame`() {
        val skin =
            runtime(
                """<text id="label" text="192" x="0" y="0" w="60" h="12"/>""" +
                    """<text id="clock" display="time" x="0" y="20" w="60" h="12"/>""",
            )
        redraws(5, skin)
        val label = checkNotNull(skin.mainLayout?.findObject("label"))
        val clock = checkNotNull(skin.mainLayout?.findObject("clock"))

        label.makiCall("settext", listOf(Value.Str("192")))
        label.setXmlParam("text", "192")
        assertEquals("the same text by setText and by setXmlParam", 0, redraws(1, skin))
        label.makiCall("settext", listOf(Value.Str("320")))
        assertEquals("a new text", 1, redraws(1, skin))

        clock.setXmlParam("text", "00:03")
        assertEquals("a script's text over the time display", 1, redraws(1, skin))
        clock.setXmlParam("text", "00:03")
        clock.makiCall("settext", listOf(Value.Str("00:03")))
        assertEquals(0, redraws(1, skin))
    }

    @Test
    fun `a strip sent to the frame it shows asks for no frame`() {
        val skin = runtime("""<animatedlayer id="vu" x="0" y="0" w="10" h="10"/>""")
        redraws(5, skin)
        val vu = checkNotNull(skin.mainLayout?.findObject("vu"))

        vu.makiCall("gotoframe", listOf(Value.Int(0)))
        assertEquals("the frame it shows", 0, redraws(1, skin))
        vu.makiCall("gotoframe", listOf(Value.Int(3)))
        assertEquals("another frame", 1, redraws(1, skin))
        vu.makiCall("gotoframe", listOf(Value.Int(3)))
        assertEquals(0, redraws(1, skin))
    }

    @Test
    fun `a playing strip that starts where it ends asks for no frame when it goes round`() {
        val skin =
            runtime(
                """<animatedlayer id="still" x="0" y="0" w="10" h="10" start="0" end="0" autoplay="1"/>""",
            )
        redraws(5, skin)
        val still = checkNotNull(skin.mainLayout?.findObject("still"))
        assertTrue("it keeps playing", still.makiCall("isplaying", emptyList())?.truthy == true)

        assertEquals("ten 200 ms steps in 2 s", 0, redraws(125, skin))
    }

    @Test
    fun `a text fading to a target draws the alphas on the way`() {
        val skin = runtime("""<text id="clock" display="time" x="0" y="0" w="60" h="12"/>""")
        player.play()
        player.pause()
        redraws(5, skin)
        val clock = checkNotNull(skin.mainLayout?.findObject("clock"))

        // Winamp2000SP4's pauseblink.m: to 0 over 0.6 s
        clock.makiCall("settargeta", listOf(Value.Int(0)))
        clock.makiCall("settargetspeed", listOf(Value.Num(0.6)))
        clock.makiCall("gototarget", emptyList())
        val drawn = ArrayList<Int>()
        repeat(40) {
            t += 16
            if (skin.tick()) {
                drawn +=
                    skin
                        .draw()
                        .filterIsInstance<DrawCmd.Text>()
                        .map { it.alpha }
                        .distinct()
                        .single()
            }
        }
        assertTrue("a frame for nearly every display frame of the fade: ${drawn.size}", drawn.size >= 36)
        assertTrue("alphas between the ends: $drawn", drawn.count { it in 32..224 } >= 15)
        assertEquals(0, drawn.last())
        assertEquals("falling all the way", drawn.sortedDescending(), drawn)
    }

    @Test
    fun `Winamp2000SP4 paused asks for a frame only when its time blinks`() {
        val files = ZipSkinFiles(File(localResource("/Winamp2000SP4.wal").toURI()))
        val skin =
            SkinRuntime(files, TestHost(files), player).apply {
                clock = { t }
                load()
            }
        player.play()
        redraws(200, skin)
        player.pause()
        redraws(200, skin)

        // its wa2songtimer.maki writes the time (or "  :  " in the dark second) 20 times a second,
        // and system.maki writes its bitrate and frequency 10 times a second; the time shows and
        // goes once a second
        val asked = redraws(625, skin) // 10 s
        assertTrue("about 10 frames in 10 s, not 220: $asked", asked in 9..11)
    }
}
