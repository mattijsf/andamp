// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.DrawPlanner
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a frame costs to build: each window's shape worked out once, and no names made for nothing. */
class PublishCostTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><elements><bitmap id="face" file="face.png"/></elements>""" +
                        """<include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<group id="body" x="0" y="0" w="150" h="60" sysregion="1" rectrgn="1">""" +
                        """<layer id="face" x="0" y="0" w="150" h="60" image="face"/>""" +
                        """<text id="clock" display="time" x="20" y="0" w="60" h="12"/>""" +
                        """<animatedlayer id="strip" x="0" y="60" w="10" h="10" image="face" frameheight="10" speed="50"/>""" +
                        """<slider id="seek" action="seek" x="0" y="70" w="150" h="10"/></group>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="pl" default_visible="0"><layout id="normal" w="200" h="80">""" +
                        """<group id="frame" x="0" y="0" w="200" h="80" sysregion="1" rectrgn="1">""" +
                        """<layer id="back" x="0" y="0" w="200" h="80" image="face"/></group>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = 200 to 80

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
    private val rt =
        SkinRuntime(files, host, player).apply {
            clock = { t }
            load()
            viewSize = 200 to 400
            openWindow(checkNotNull(container("pl")))
            drawWindows()
        }

    /** Everything a frame asks of the windows' shapes and edges, as the app asks it. */
    private fun frame() {
        rt.drawWindows()
        rt.stackSize
        rt.drawnSize
        for (w in rt.windows) {
            w.contentRight()
            w.contentBottom()
            w.snappedSize()
        }
    }

    @Test
    fun `drawWindows works out each window's shape once`() {
        rt.invalidate()
        val before = rt.shapesWorkedOut
        frame()
        assertEquals("once for each of the two windows", before + 2, rt.shapesWorkedOut)

        // playing, only the time moves: the shapes stay
        player.play()
        repeat(120) {
            t += 16
            if (rt.tick()) frame()
        }
        assertEquals(before + 2, rt.shapesWorkedOut)
    }

    @Test
    fun `a window keeps its shape command from frame to frame until the skin changes`() {
        fun shape() =
            rt
                .drawWindows()
                .first()
                .second
                .filterIsInstance<DrawCmd.WindowStart>()
                .single()
        val first = shape()
        assertSame(first, shape())
        assertSame(first.region, shape().region)

        rt.invalidate()
        assertTrue("a change makes it again", shape() !== first)
    }

    @Test
    fun `a playing strip, a moving thumb and a fade keep each window's shape`() {
        player.lengthMs = 10_000
        val strip = checkNotNull(rt.mainLayout?.findObject("strip"))
        strip.makiCall("play", emptyList())
        player.play()

        fun shape() =
            rt
                .drawWindows()
                .first()
                .second
                .filterIsInstance<DrawCmd.WindowStart>()
                .single()
        val first = shape()
        val before = rt.shapesWorkedOut
        var frames = 0
        repeat(120) {
            t += 16
            if (it == 60) rt.mainLayout?.findObject("face")?.makiCall("setalpha", listOf(Value.Int(128)))
            if (rt.tick()) {
                frames++
                assertSame(first, shape())
            }
        }
        assertTrue("the strip and the thumb moved: $frames", frames > 20)
        assertEquals(before, rt.shapesWorkedOut)
    }

    @Test
    fun `the draw commands every skin shares keep no object, so no skin stays in memory through them`() {
        rt.drawWindows()
        rt.draw()
        assertNull(DrawCmd.Unclip.ownedBy)
        assertNull(DrawCmd.WindowEnd.ownedBy)
        assertNull(DrawCmd.Unclip.owner)
    }

    @Test
    fun `drawing a frame builds no owner names unless one is read`() {
        val before = DrawCmd.ownerNamesBuilt
        val stack = rt.drawWindows()
        assertEquals(before, DrawCmd.ownerNamesBuilt)

        val planner = DrawPlanner()
        player.play()
        repeat(60) {
            t += 16
            rt.tick()
            planner.draw(rt, t)
        }
        assertEquals("planning builds none either", before, DrawCmd.ownerNamesBuilt)

        val face = stack.first().second.first { it.owner == "layer#face" }
        assertTrue(DrawCmd.ownerNamesBuilt > before)
        assertEquals("layer#face", face.owner)
    }
}
