// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Layout
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.StackArrange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host reads a skin's windows by id ([SkinRuntime.windowIds], [SkinRuntime.windowInfo]): each
 * layout keeps one id, a snapshot tells what the window is like when it is read, and the stack
 * measures give what [StackArrange] gives for the same windows.
 */
class WindowInfoTest {
    // a main window 300x200 with a normal and a shade layout whose body shows 200x120, a playlist
    // with a resize grip, and a window held at 340 px wide
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1">""" +
                        """<layout id="normal" w="300" h="200" minimum_w="250" minimum_h="150">""" +
                        """<group id="body" x="0" y="0" w="200" h="120" sysregion="1" rectrgn="1"/></layout>""" +
                        """<layout id="shade" w="300" h="14"/></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="300" h="80">""" +
                        """<component id="list" param="guid:{45F3F7C1-A6F3-4ee6-A15E-125E92FC3F8D}" """ +
                        """x="0" y="0" w="290" h="70"/>""" +
                        """<layer id="pl.sizer" resize="bottomright" x="290" y="70" w="10" h="10"/>""" +
                        """</layout></container>""" +
                        """<container id="wide" name="Wide" default_visible="0">""" +
                        """<layout id="normal" w="340" h="40" minimum_w="340" maximum_w="340"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt =
        SkinRuntime(files, TestHost(files), FakePlayer().apply { clock = { 0L } }).apply {
            clock = { 0L }
            load()
        }

    private fun open(vararg ids: String) = ids.forEach { rt.openWindow(checkNotNull(rt.container(it))) }

    private fun window(id: String): Layout = checkNotNull(rt.container(id)?.current)

    @Test
    fun `the windows on screen are named by id, the main one first`() {
        val main = checkNotNull(rt.mainWindow)
        assertEquals(listOf(main), rt.windowIds)

        open("pl", "wide")
        assertEquals(listOf(main, window("pl").windowId, window("wide").windowId), rt.windowIds)
        assertEquals(3, rt.windowIds.toSet().size)
    }

    @Test
    fun `a window keeps its id, and its shade is another window`() {
        val normal = checkNotNull(rt.mainWindow)
        open("pl")
        val pl = window("pl").windowId
        rt.closeWindow(checkNotNull(rt.container("pl")))
        open("pl")
        assertEquals("the same layout, the same id", pl, window("pl").windowId)

        checkNotNull(rt.mainContainer).makiCall("switchtolayout", listOf(Value.Str("shade")))
        rt.ensureLayout()
        val shade = checkNotNull(rt.mainWindow)
        assertNotEquals(normal, shade)
        assertEquals(300 to 14, rt.windowSize(shade))
    }

    @Test
    fun `a snapshot tells the window's size, limits and handles as it is read`() {
        val main = rt.windowInfo(checkNotNull(rt.mainWindow))
        assertEquals(300 to 200, main.size)
        assertEquals(listOf(250, 150, 0, 0), listOf(main.minW, main.minH, main.maxW, main.maxH))
        assertFalse(main.resizableBothWays)
        assertNull(main.edgeX)

        open("pl", "wide")
        assertTrue("a bottom-right grip resizes both ways", rt.windowInfo(window("pl").windowId).resizableBothWays)
        val wide = rt.windowInfo(window("wide").windowId)
        assertEquals(340 to 340, wide.minW to wide.maxW)
    }

    @Test
    fun `resizing goes through the id, and an older snapshot keeps what it read`() {
        val id = checkNotNull(rt.mainWindow)
        val before = rt.windowInfo(id)
        rt.resizeWindow(id, 400, 260)
        assertEquals(400 to 260, rt.windowSize(id))
        assertEquals(400 to 260, rt.windowInfo(id).size)
        assertEquals(300 to 200, before.size)
    }

    @Test
    fun `the stack measures are StackArrange's for the windows on screen`() {
        open("pl", "wide")
        rt.viewSize = 200 to 1000
        rt.columns = 2
        rt.ensureLayout()
        val main = window("main")
        val others = rt.windows.drop(1)

        assertEquals(StackArrange.shownWidth(main, others, 0), rt.shownWidth())
        assertEquals(StackArrange.shownWidth(main, others, 220), rt.shownWidth(220))
        assertEquals(StackArrange.naturalSize(main, others, 1, rt::showsList), rt.naturalStackSize(1))
        assertEquals(StackArrange.naturalSize(main, others, 2, rt::showsList, 200), rt.naturalStackSize(2, 200))
        assertEquals(StackArrange.leftColumn(main, others, 2, rt::showsList, rt::windowRect), rt.leftColumn())
        assertTrue(rt.listWindowOpen)
        assertEquals(main.contentRight(), rt.contentRight(main.windowId))
    }

    @Test
    fun `with only the main window there is no list and no left column`() {
        rt.columns = 2
        assertFalse(rt.listWindowOpen)
        assertNull(rt.leftColumn())
    }

    @Test
    fun `windows open, close and are found by their container's id`() {
        assertFalse("no such container", rt.openWindow("none"))
        assertTrue(rt.openWindow("pl"))
        val pl = checkNotNull(rt.windowOf("pl"))
        assertTrue(pl in rt.windowIds)
        assertEquals(rt.windowRect(window("pl")), rt.windowRect(pl))
        assertEquals("pl" to "normal", rt.windowInfo(pl).let { it.container to it.layout })

        assertTrue(rt.closeWindow("pl"))
        assertFalse(pl in rt.windowIds)
        assertEquals("a closed window keeps its id", pl, rt.windowOf("pl"))
        assertNull(rt.windowOf("none"))
    }

    @Test
    fun `an object is found in the windows on screen, with where it sits in its window and in the stack`() {
        rt.presentation = rt.presentation.copy(viewSize = 300 to 1000)
        open("pl")
        rt.ensureLayout()

        val list = checkNotNull(rt.objectInfo("list"))
        val pl = rt.windowRect(window("pl"))
        assertEquals(window("pl").windowId, list.window)
        assertEquals(Rect(0, 0, 290, 70), list.rect)
        assertEquals(Rect(pl.left, pl.top, 290, 70), list.stackRect)
        assertTrue(list.visible)

        assertEquals("Group", rt.objectInfo("body")?.className)
        assertNull(rt.objectInfo("none"))
    }
}
