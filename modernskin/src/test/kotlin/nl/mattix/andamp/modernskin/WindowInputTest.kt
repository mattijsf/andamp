// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.fixtures.placeAsScript
import nl.mattix.andamp.modernskin.fixtures.switchLayoutAsScript
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SkinSetup
import nl.mattix.andamp.modernskin.skin.WindowId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A host that shows each window apart talks to the engine window by window: touches in a
 * window's own pixels and kept to that window, the window placed where the host put it, focus by
 * id, and a word whenever the windows on screen change.
 */
class WindowInputTest {
    // a main window with a button along its bottom edge, and a playlist window with one along its top
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="100" h="40">""" +
                        """<button id="min" action="MINIMIZE" x="0" y="30" w="20" h="10"/></layout>""" +
                        """<layout id="shade" w="100" h="14"/></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="100" h="40">""" +
                        """<button id="next" action="VIS_NEXT" x="0" y="0" w="20" h="10"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val asked = ArrayList<String>()
    private val told = ArrayList<List<WindowId>>()

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

            override fun hostAction(
                action: String,
                param: String?,
            ) {
                asked += action
            }

            override fun windowsChanged(windows: List<WindowId>) {
                told += windows
            }
        }

    private val rt =
        SkinRuntime(files, host, FakePlayer(), SkinSetup(hostActions = true), clock = { 0L }).apply {
            load()
            presentation = presentation.copy(viewSize = 100 to 400, touchSlop = 6)
            openWindow("pl")
            ensureLayout()
        }

    private val main get() = checkNotNull(rt.mainWindow)
    private val pl get() = checkNotNull(rt.windowOf("pl"))

    @Test
    fun `a touch in a window's own pixels presses what the same point of the stack presses`() {
        rt.pointerDown(pl, 5, 5)
        rt.pointerUp(pl, 5, 5)
        val r = rt.windowRect(pl)
        rt.pointerDown(r.left + 5, r.top + 5)
        rt.pointerUp(r.left + 5, r.top + 5)
        assertEquals(listOf("VIS_NEXT", "VIS_NEXT"), asked)
    }

    @Test
    fun `a touch named for a window stays in it, however near another window's control`() {
        val r = rt.windowRect(pl)
        // 2 px above the playlist: on the stack that is the main window's button
        rt.pointerDown(r.left + 5, r.top - 2)
        rt.pointerUp(r.left + 5, r.top - 2)
        // the same point, named for the playlist: its own button, within the touch slop
        rt.pointerDown(pl, 5, -2)
        rt.pointerUp(pl, 5, -2)
        assertEquals(listOf("MINIMIZE", "VIS_NEXT"), asked)
    }

    @Test
    fun `a window the host places is where scripts read it`() {
        rt.moveWindow(pl, 300, 120)
        assertEquals(300 to 120, rt.placeAsScript(pl))
    }

    @Test
    fun `the host hears each change of the windows on screen`() {
        assertEquals("the main window, then the playlist", listOf(listOf(main), listOf(main, pl)), told)
        told.clear()

        rt.switchLayoutAsScript("main", "shade")
        rt.ensureLayout()
        val shade = checkNotNull(rt.mainWindow)
        rt.closeWindow("pl")
        rt.ensureLayout()
        assertEquals(listOf(listOf(shade, pl), listOf(shade)), told)

        told.clear()
        rt.ensureLayout()
        assertEquals("nothing changed, nothing told", emptyList<List<WindowId>>(), told)
    }

    @Test
    fun `focus goes to a window by id`() {
        rt.focus(pl)
        assertEquals(true, rt.hasFocus(rt.windows.single { it.windowId == pl }))
    }
}
