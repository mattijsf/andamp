// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SystemObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A drawer that shows Winamp's visualizer, as Winamp Modern's does: where the app draws its
 * visualizer, the drawer's Maximize, and its Detach Visualizer.
 */
class VisDrawerTest {
    // a main window 200 by 100 whose drawer group, 40 px high, shows 30 px of a visualizer holder
    // under a 10 px button bar
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<group id="drawer" x="10" y="60" w="180" h="40">""" +
                        """<windowholder id="vis" x="0" y="10" w="180" h="60" hold="guid:avs" visible="0"/>""" +
                        """</group></layout></container>""" +
                        """<container id="avs" name="Visualizations" default_visible="0" """ +
                        """component="guid:{0000000A-000C-0010-FF7B-01014263450C}"><layout id="normal" w="200" h="100">""" +
                        """<button id="button.vis.reattach" action="EQ_TOGGLE" x="0" y="0" w="20" h="20"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val asked = ArrayList<Pair<String, String?>>()

    private val player = FakePlayer().apply { clock = { 0L } }

    private val rt =
        SkinRuntime(files, TestHost(files), player).apply {
            clock = { 0L }
            viewSize = 200 to 1000
            load()
            onHostAction = { a, p -> asked += a to p }
        }

    private val main get() = checkNotNull(rt.mainLayout)

    private fun call(
        name: String,
        vararg ints: Int,
    ) = ints.map { Value.Int(it) }.let { args -> main.makiCall(name, args) }

    private fun showVis(shown: Boolean) = main.findObject("vis")!!.makiCall(if (shown) "show" else "hide", emptyList())

    @Test
    fun `the visualizer shows where its holder shows, cut to the drawer around it`() {
        assertNull("hidden", rt.visRect)

        showVis(true)
        assertEquals(Rect(10, 70, 180, 30), rt.visRect)

        showVis(false)
        assertNull(rt.visRect)
    }

    @Test
    fun `a holder for the video, which Andamp does not play, shows the visualizer too`() {
        val holder = main.findObject("vis")!!
        holder.attrs["hold"] = "guid:{F0816D7B-FFFC-4343-80F2-E8199AA15CC3}"
        showVis(true)

        assertEquals(Rect(10, 70, 180, 30), rt.visRect)
    }

    @Test
    fun `a drawer shut so far that none of its holder shows shows no visualizer`() {
        showVis(true)
        main.findObject("drawer")!!.makiCall("resize", listOf(Value.Int(10), Value.Int(60), Value.Int(180), Value.Int(10)))

        assertNull(rt.visRect)
    }

    @Test
    fun `a maximize a tap asks for shows the fullscreen visualizer and leaves the window as it is`() {
        rt.firingEvent("onleftclick") { call("resize", 0, 0, 200, 1100) }

        assertEquals(200 to 100, main.size)
        assertEquals(listOf("VIS_FS" to null), asked)
    }

    @Test
    fun `a maximize a script repeats by itself leaves the window as it is and asks for nothing`() {
        rt.firingEvent("onsnapadjustchanged") { call("resize", 0, 0, 200, 1100) }

        assertEquals(200 to 100, main.size)
        assertEquals(emptyList<Pair<String, String?>>(), asked)
    }

    @Test
    fun `a resize short of the viewport resizes the window`() {
        rt.firingEvent("onleftclick") { call("resize", 0, 0, 200, 300) }

        assertEquals(200 to 300, main.size)
        assertEquals(emptyList<Pair<String, String?>>(), asked)
    }

    @Test
    fun `a script sees the visualizer's window as visible while the skin shows the visualizer`() {
        val system = SystemObject(rt, null, "", "test")

        fun visible() =
            system.makiCall("isnamedwindowvisible", listOf(Value.Str("{0000000A-000C-0010-FF7B-01014263450C}")))!!.asInt

        assertEquals(0, visible())
        showVis(true)
        assertEquals(1, visible())
    }

    @Test
    fun `a script showing the visualizer's window asks the app to show it, and a window the app has not stays shut`() {
        val system = SystemObject(rt, null, "", "test")

        system.makiCall("showwindow", listOf(Value.Str("{0000000A-000C-0010-FF7B-01014263450C}"), Value.Str(""), Value.Int(0)))
        system.makiCall("showwindow", listOf(Value.Str("{F0816D7B-FFFC-4343-80F2-E8199AA15CC3}"), Value.Str(""), Value.Int(0)))
        system.makiCall("showwindow", listOf(Value.Str("{12345678-0000-0000-0000-000000000000}"), Value.Str(""), Value.Int(0)))

        assertEquals(listOf("SHOW" to "avs", "SHOW" to "video"), asked)
    }

    @Test
    fun `the visualizer window closing presses the reattach button of the window a script had the app show, once`() {
        val system = SystemObject(rt, null, "", "test")
        rt.visualizerWindowClosed()
        assertEquals("nothing shown, nothing pressed", false, player.eqEnabled)

        system.makiCall("showwindow", listOf(Value.Str("{0000000A-000C-0010-FF7B-01014263450C}"), Value.Str(""), Value.Int(0)))
        rt.visualizerWindowClosed()
        rt.visualizerWindowClosed()

        assertEquals(true, player.eqEnabled)
        assertEquals("the skin's window stays shut", 1, rt.windows.size)
    }
}
