// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Layout
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.StackArrange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * How a phone sets out a skin's windows as one stack ([StackArrange]): each window docked under
 * what shows of the main one at its natural height, the last list window filling the height
 * upright, and a touch beside the stack meaning the nearest window.
 */
class StackArrangeTest {
    // a main window 300x200 whose shaped body shows 200x120 of it, an equalizer and a playlist,
    // both of which can be made taller
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="200">""" +
                        """<group id="body" x="0" y="0" w="200" h="120" sysregion="1" rectrgn="1"/>""" +
                        """</layout></container>""" +
                        """<container id="eq" name="Equalizer" default_visible="0"><layout id="normal" w="300" h="60">""" +
                        """<layer id="eq.sizer" resize="bottomright" x="290" y="50" w="10" h="10"/>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="300" h="80">""" +
                        """<component id="list" param="guid:{45F3F7C1-A6F3-4ee6-A15E-125E92FC3F8D}" """ +
                        """x="0" y="0" w="290" h="70"/>""" +
                        """<layer id="pl.sizer" resize="bottomright" x="290" y="70" w="10" h="10"/>""" +
                        """</layout></container>""",
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

    private fun rectOf(id: String): Rect {
        rt.ensureLayout()
        return rt.windowRect(window(id))
    }

    @Test
    fun `one column docks each window under what shows of the main window, as wide as it`() {
        rt.viewSize = 200 to 1000
        rt.stretchLast = false
        open("eq", "pl")

        assertEquals(Rect(0, 0, 300, 200), rectOf("main"))
        assertEquals("under the body, as wide as it", Rect(0, 120, 200, 60), rectOf("eq"))
        assertEquals(Rect(0, 180, 200, 80), rectOf("pl"))
        assertEquals(300 to 260, rt.stackSize)
    }

    @Test
    fun `upright, the last list window takes the height left`() {
        rt.viewSize = 200 to 1000
        open("eq", "pl")

        assertEquals(Rect(0, 120, 200, 60), rectOf("eq"))
        assertEquals("the playlist fills the view", Rect(0, 180, 200, 820), rectOf("pl"))

        // the equalizer last shows no list, so it keeps its height, and so does the playlist above it
        rt.closeWindow(checkNotNull(rt.container("pl")))
        open("pl")
        rt.closeWindow(checkNotNull(rt.container("eq")))
        open("eq")
        assertEquals(Rect(0, 120, 200, 80), rectOf("pl"))
        assertEquals(Rect(0, 200, 200, 60), rectOf("eq"))
    }

    @Test
    fun `a docked window's natural height is the one the skin gives it, also while it is stretched`() {
        rt.viewSize = 200 to 1000
        open("eq", "pl")
        assertEquals(Rect(0, 180, 200, 820), rectOf("pl"))

        assertEquals(80, StackArrange.naturalHeight(window("pl")))
        assertEquals(60, StackArrange.naturalHeight(window("eq")))
        assertEquals("asking leaves the window as it was", 820, window("pl").size.second)
        assertEquals(Rect(0, 180, 200, 820), rectOf("pl"))
    }

    @Test
    fun `the playlist shows a list, the main window and the equalizer do not`() {
        open("eq", "pl")

        assertEquals(listOf(false, false, true), rt.windows.map(rt::showsList))
    }

    @Test
    fun `without a view every window keeps its size`() {
        open("eq", "pl")

        assertEquals(Rect(0, 0, 300, 200), rectOf("main"))
        assertEquals(Rect(0, 120, 300, 60), rectOf("eq"))
        assertEquals(Rect(0, 180, 300, 80), rectOf("pl"))
    }

    @Test
    fun `a touch just below the stack finds the nearest window within the slop`() {
        rt.viewSize = 200 to 1000
        rt.stretchLast = false
        open("eq", "pl")
        rt.ensureLayout()
        val slop = 6

        fun at(
            x: Int,
            y: Int,
        ) = StackArrange.windowAt(rt.windows, rt::windowRect, x to y, slop)

        assertSame("the playlist ends at 260", window("pl"), at(10, 259 + slop))
        assertNull("past the slop", at(10, 260 + slop))
        assertSame("the equalizer covers the main window's empty space under the body", window("eq"), at(10, 150))
        assertSame("beside the equalizer the main window", window("main"), at(250, 150))
    }
}
