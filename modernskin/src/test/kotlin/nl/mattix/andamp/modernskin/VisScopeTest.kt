// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The vis object's oscilloscope as Winamp 5.666 draws it: a point per column from every fourth
 * sample of the waveform, silence on row 8 and a positive sample below it, the three oscstyle
 * looks, and the colors by row.
 */
class VisScopeTest {
    /** A main window holding one 72 x 16 vis, the size of the picture it draws, so nothing is stretched. */
    private fun files(attributes: String) =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="72" h="16">""" +
                        """<vis id="vis" x="0" y="0" w="72" h="16" mode="2" $attributes/>""" +
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

    /** The waveform the player hands the skin. */
    private var wave = FloatArray(0)

    private val player =
        object : Player by FakePlayer() {
            override fun waveform(): FloatArray = wave
        }

    private fun runtime(
        attributes: String = "",
        player: Player = this.player,
    ) = SkinRuntime(files(attributes), host, player).apply { load() }

    /** The vis picture of one frame, 72 x 16 ARGB. */
    private fun picture(rt: SkinRuntime): IntArray {
        rt.tick()
        val pixels = rt.draw().filterIsInstance<DrawCmd.Pixels>().single()
        assertEquals(72 to 16, pixels.w to pixels.h)
        return pixels.argb
    }

    /** The rows lit in column [x]. */
    private fun IntArray.column(x: Int): List<Int> = (0 until 16).filter { this[it * 72 + x] != 0 }

    /** A waveform of 576 samples whose every fourth sample, the one a column takes, lands on [rows]; the rest are loud. */
    private fun waveOnRows(vararg rows: Int): FloatArray =
        FloatArray(576) { i ->
            if (i % 4 == 0) (rows[(i / 4) % rows.size] - 8 + 0.5f) / 16f else 0.99f
        }

    @Test
    fun `with no waveform the scope is a flat white line on row 8`() {
        val px = picture(runtime())

        for (x in 0 until 72) assertEquals("column $x", listOf(8), px.column(x))
        assertEquals(0xffffffff.toInt(), px[8 * 72])
    }

    @Test
    fun `a column takes every fourth sample, eight rows to the unit, a positive sample below the middle`() {
        wave = waveOnRows(*IntArray(16) { it })
        val px = picture(runtime("""oscstyle="dots""""))

        for (x in 0 until 72) assertEquals("column $x", listOf(x % 16), px.column(x))
        wave = FloatArray(576) { if (it % 4 == 0) 0.25f else -1f }
        assertEquals("a quarter of full scale is four rows down", listOf(12), picture(runtime("""oscstyle="dots"""")).column(0))
        wave = FloatArray(576) { if (it % 4 == 0) -1f else 1f }
        assertEquals("full scale stops at the edge", listOf(0), picture(runtime("""oscstyle="dots"""")).column(0))
    }

    @Test
    fun `a waveform of another length is read at the same share of it`() {
        wave = FloatArray(288) { i -> if (i % 2 == 0) ((i / 2) % 16 - 8 + 0.5f) / 16f else 0.99f }
        val px = picture(runtime("""oscstyle="dots""""))

        for (x in 0 until 72) assertEquals("column $x", listOf(x % 16), px.column(x))
    }

    @Test
    fun `solid joins each point to the last one`() {
        wave = waveOnRows(8, 12, 4, 4, 10, 9)
        val px = picture(runtime("""oscstyle="Solid""""))

        assertEquals("the first point alone", listOf(8), px.column(0))
        assertEquals("down: from the last point to this one", (8..12).toList(), px.column(1))
        assertEquals("up: from this one to the row above the last", (4..11).toList(), px.column(2))
        assertEquals(listOf(4), px.column(3))
        assertEquals((4..10).toList(), px.column(4))
        assertEquals((9..9).toList(), px.column(5))
    }

    @Test
    fun `lines fills each point to the middle`() {
        wave = waveOnRows(3, 8, 12, 7, 0, 15)
        val px = picture(runtime("""oscstyle="lines""""))

        assertEquals((3..7).toList(), px.column(0))
        assertEquals(listOf(8), px.column(1))
        assertEquals((8..12).toList(), px.column(2))
        assertEquals(listOf(7), px.column(3))
        assertEquals((0..7).toList(), px.column(4))
        assertEquals((8..15).toList(), px.column(5))
    }

    @Test
    fun `the scope joins its points until a skin says otherwise, and an unknown style changes nothing`() {
        wave = waveOnRows(8, 12)
        assertEquals("a line by default", (8..12).toList(), picture(runtime()).column(1))

        val rt = runtime("""oscstyle="DOTS"""")
        assertEquals(listOf(12), picture(rt).column(1))
        checkNotNull(rt.mainLayout?.findObject("vis")).setParam("oscstyle", "zigzag")
        assertEquals("still dots", listOf(12), picture(rt).column(1))
    }

    @Test
    fun `a column takes its point's row color, colorosc1 in the middle and colorosc5 at the top`() {
        wave = waveOnRows(*IntArray(16) { it })
        val px = picture(runtime("""oscstyle="dots""""))
        val classic = intArrayOf(0xFFFFFF, 0xD6D6DE, 0xB5BDBD, 0xA0AAAF, 0x949CA5)
        val byRow = intArrayOf(4, 4, 3, 3, 2, 2, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3)

        for (row in 0 until 16) assertEquals("row $row", classic[byRow[row]] or OPAQUE, px[row * 72 + row])

        val third = picture(runtime("""oscstyle="dots" colorosc3="1,2,3""""))
        assertEquals("colorosc3 on rows 4 and 5, 12 and 13", 0x010203 or OPAQUE, third[12 * 72 + 12])
        assertEquals(0x010203 or OPAQUE, third[5 * 72 + 5])
        assertEquals("the others keep theirs", 0xFFFFFF or OPAQUE, third[8 * 72 + 8])
        val all = picture(runtime("""oscstyle="dots" colorallosc="10,20,30""""))
        for (row in 0 until 16) assertEquals("colorallosc on row $row", 0x0a141e or OPAQUE, all[row * 72 + row])
    }

    @Test
    fun `the demo player's scope moves while it plays and lies flat once it stops`() {
        var now = 0L
        val demo = FakePlayer().apply { clock = { now } }
        val rt = runtime("""oscstyle="dots"""", demo)
        demo.play()

        val first = picture(rt)
        now += 200
        val second = picture(rt)

        assertNotEquals(first.toList(), second.toList())
        assertNotEquals("not a flat line", listOf(8), (0 until 72).flatMap { first.column(it) }.distinct())
        demo.stop()
        val stopped = picture(rt)
        for (x in 0 until 72) assertEquals(listOf(8), stopped.column(x))
    }

    private companion object {
        const val OPAQUE = 0xff000000.toInt()
    }
}
