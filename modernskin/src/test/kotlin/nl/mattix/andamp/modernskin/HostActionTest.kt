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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Button actions the app answers reach it; what the skin does itself stays inside. */
class HostActionTest {
    // a main window with buttons for each kind of action, a playlist window with a close button, and
    // a frame the skin keeps for Winamp's library
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
    fun `minimize, vis buttons, closing the main window and the app's windows go to the app by name`() {
        rt.load()
        rt.onHostAction = { a, p -> asked += a to p }

        listOf("min", "vis", "close", "library", "library.alias", "visualizer").forEach(::tap)

        assertEquals(
            listOf(
                "MINIMIZE" to null,
                "VIS_NEXT" to null,
                "CLOSE" to null,
                "TOGGLE" to "ml",
                "TOGGLE" to "ml",
                "TOGGLE" to "avs",
            ),
            asked,
        )
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
