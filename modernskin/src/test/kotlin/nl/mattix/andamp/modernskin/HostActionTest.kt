// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SystemObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Button actions the app answers reach it; what the skin does itself stays inside. */
class HostActionTest {
    // a main window with buttons for each kind of action, a file info button among them, and a song
    // ticker a double tap asks file info from, a menu bar entry; a playlist window with a close button, and a frame the
    // skin keeps for Winamp's library
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="40">""" +
                        """<button id="min" action="MINIMIZE" x="0" y="0" w="20" h="20"/>""" +
                        """<button id="vis" action="VIS_NEXT" x="40" y="0" w="20" h="20"/>""" +
                        """<button id="close" action="CLOSE" x="80" y="0" w="20" h="20"/>""" +
                        """<button id="library" action="TOGGLE" param="guid:ml" x="120" y="0" w="20" h="20"/>""" +
                        """<button id="playlist" action="TOGGLE" param="pl" x="160" y="0" w="20" h="20"/>""" +
                        """<button id="library.alias" action="TOGGLE" param="library" x="200" y="0" w="20" h="20"/>""" +
                        """<button id="visualizer" action="TOGGLE" param="guid:{0000000A-000C-0010-FF7B-01014263450C}" """ +
                        """x="240" y="0" w="20" h="20"/>""" +
                        """<button id="info" action="trackinfo" x="270" y="0" w="20" h="20"/>""" +
                        """<text id="ticker" display="songtitle" dblclickaction="trackinfo" x="0" y="25" w="200" h="10"/>""" +
                        """<layer id="options.up" x="210" y="25" w="40" h="10"/>""" +
                        """<layer id="options.down" x="210" y="25" w="40" h="10" visible="0"/>""" +
                        """<menu id="options" menu="WA5:Options" menugroup="main" normal="options.up" down="options.down" """ +
                        """x="210" y="25" w="40" h="10"/>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="300" h="40">""" +
                        """<button id="pl.close" action="CLOSE" x="0" y="0" w="20" h="20"/>""" +
                        """</layout></container>""" +
                        """<container id="mlframe" name="Media Library" default_visible="0" """ +
                        """component="guid:{6B0EDF80-C9A5-11D3-9F26-00C04F39FFC6}">""" +
                        """<layout id="normal" w="300" h="80"/></container>""",
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

    private val rt = SkinRuntime(files, host, FakePlayer()).apply { viewSize = 300 to 600 }
    private val asked = ArrayList<Pair<String, String?>>()

    private fun tap(id: String) {
        rt.ensureLayout()
        val o: GuiObject = checkNotNull(rt.windows.firstNotNullOfOrNull { it.findObject(id) }) { id }
        val r = rt.windows.first { it.findObject(id) != null }.let { l -> rt.windowRect(l) }
        val x = r.left + o.rect.left + 2
        val y = r.top + o.rect.top + 2
        rt.pointerDown(x, y)
        rt.pointerUp(x, y)
    }

    @Test
    fun `minimize, vis buttons, file info, closing the main window and the app's windows go to the app by name`() {
        rt.load()
        rt.onHostAction = { a, p -> asked += a to p }

        listOf("min", "vis", "close", "library", "library.alias", "visualizer", "info").forEach(::tap)

        assertEquals(
            listOf(
                "MINIMIZE" to null,
                "VIS_NEXT" to null,
                "CLOSE" to null,
                "TOGGLE" to "ml",
                "TOGGLE" to "ml",
                "TOGGLE" to "avs",
                "TRACKINFO" to null,
            ),
            asked,
        )
    }

    @Test
    fun `a menu bar entry shows its pressed picture while held and asks the app for the menu it names`() {
        rt.load()
        rt.onHostAction = { a, p -> asked += a to p }
        rt.ensureLayout()
        val layout = checkNotNull(rt.mainLayout)
        val entry = checkNotNull(layout.findObject("options"))
        val x = entry.rect.left + 2
        val y = entry.rect.top + 2

        rt.pointerDown(x, y)
        assertTrue("pressed", layout.findObject("options.down")!!.visible)
        assertFalse(layout.findObject("options.up")!!.visible)
        rt.pointerUp(x, y)

        assertEquals(listOf("MENU" to "WA5:Options"), asked)
        assertTrue("let go", layout.findObject("options.up")!!.visible)
        assertFalse(layout.findObject("options.down")!!.visible)
    }

    @Test
    fun `a menu bar entry keeps a tap on it from a button just above it, and a press on it moves no window`() {
        rt.load()
        rt.touchSlop = 10
        rt.onHostAction = { a, p -> asked += a to p }
        rt.ensureLayout()
        val entry = checkNotNull(rt.mainLayout?.findObject("options"))
        // 7 px under the library button, within the slop
        val x = entry.rect.left + 2
        val y = entry.rect.top + 2

        rt.pointerDown(x, y, seq = 1)
        assertNull("no window drag", rt.moveTarget)
        rt.pointerUp(x, y)

        assertEquals(listOf("MENU" to "WA5:Options"), asked)
    }

    @Test
    fun `a double tap on a song ticker whose double click action is trackinfo asks the app for file info`() {
        rt.load()
        rt.onHostAction = { a, p -> asked += a to p }
        rt.ensureLayout()
        val ticker = checkNotNull(rt.mainLayout?.findObject("ticker"))

        rt.doubleTap(ticker.rect.left + 20, ticker.rect.top + 5)

        assertEquals(listOf("TRACKINFO" to null), asked)
    }

    @Test
    fun `with a host the skin's frame for the library stays shut, and without one it opens as in Winamp`() {
        rt.load()
        rt.onHostAction = { a, p -> asked += a to p }
        tap("library")
        assertEquals("only the main window", 1, rt.windows.size)

        rt.onHostAction = null
        tap("library")
        assertEquals("the library frame opened", 2, rt.windows.size)
    }

    @Test
    fun `a skin window opens and closes inside the skin`() {
        rt.load()
        rt.onHostAction = { a, p -> asked += a to p }

        tap("playlist")
        assertTrue("the playlist window opened", rt.windows.size == 2)
        tap("pl.close")

        assertEquals(1, rt.windows.size)
        assertTrue(asked.toString(), asked.isEmpty())
    }

    @Test
    fun `scripts read the phone's view as the screen`() {
        val system = SystemObject(rt, null, "", "test")

        assertEquals(300, system.makiCall("getviewportwidth", emptyList())!!.asInt)
        assertEquals(600, system.makiCall("getviewportheight", emptyList())!!.asInt)
        rt.viewSize = null
        assertEquals(1080, system.makiCall("getviewportwidth", emptyList())!!.asInt)
    }
}
