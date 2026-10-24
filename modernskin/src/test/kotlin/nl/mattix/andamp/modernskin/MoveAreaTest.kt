// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The skin's move areas: a press on one is a press the host may turn into moving the skin
 * ([SkinRuntime.moveTarget]), as Winamp moves a window by them.
 */
class MoveAreaTest {
    // a bar that moves the window, with a button on it; a panel group that moves it, holding a
    // background and a tab that says it does not; a title bar the skin defines, whose mousetrap
    // writes no move; a layer that writes nothing; a slider that says it moves
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<groupdef id="panel">""" +
                        """<layer id="panel.bg" x="0" y="0" w="200" h="60"/>""" +
                        """<layer id="panel.tab" x="0" y="0" w="40" h="20" move="0"/>""" +
                        """</groupdef>""" +
                        """<groupdef id="wasabi.titlebar">""" +
                        """<layer id="mousetrap" x="0" y="0" w="0" h="0" relatw="1" relath="1"/>""" +
                        """</groupdef>""" +
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="300">""" +
                        """<layer id="bar" x="0" y="0" w="300" h="14" move="1"/>""" +
                        """<button id="close" x="280" y="2" w="10" h="10"/>""" +
                        """<group id="panel" x="0" y="50" w="200" h="60" move="1"/>""" +
                        """<group id="wasabi.titlebar" x="0" y="150" w="300" h="14"/>""" +
                        """<layer id="plain" x="0" y="200" w="100" h="30"/>""" +
                        """<slider id="volume" x="150" y="250" w="100" h="10" move="1"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt = SkinRuntime(files, TestHost(files), FakePlayer()).apply { load() }

    private fun find(id: String): GuiObject = checkNotNull(checkNotNull(rt.mainLayout).findObject(id)) { id }

    /** A press at a point of [id], the touch [seq], then its release: the move target as the press left it. */
    private fun pressOn(
        id: String,
        seq: Int,
        dx: Int = 2,
        dy: Int = 2,
    ): Int? {
        val r = find(id).rect
        rt.pointerDown(r.left + dx, r.top + dy, seq)
        val target = rt.moveTarget
        rt.pointerUp(r.left + dx, r.top + dy)
        return target
    }

    @Test
    fun `a press on an object that writes move="1" is a move target`() {
        assertEquals(1, pressOn("bar", seq = 1, dx = 100))
    }

    @Test
    fun `a button on a move area takes the press, and moves nothing`() {
        assertNull(pressOn("close", seq = 2))
    }

    @Test
    fun `a control that says it moves still works as a control`() {
        assertNull(pressOn("volume", seq = 3, dx = 50))
    }

    @Test
    fun `an object inside a group that moves moves it too, unless it says it does not`() {
        assertEquals("the panel's background", 4, pressOn("panel.bg", seq = 4, dx = 100, dy = 40))
        assertNull("the tab that writes move=0", pressOn("panel.tab", seq = 5))
    }

    @Test
    fun `a wasabi title bar's mousetrap moves the window though it writes no move`() {
        assertNull("the title bar the skin defines writes none", find("mousetrap").move)

        assertEquals(6, pressOn("mousetrap", seq = 6, dx = 150))
    }

    @Test
    fun `a layer that writes nothing, and a point on no object, are no move target`() {
        assertNull(pressOn("plain", seq = 7))
        rt.pointerDown(290, 290, seq = 8)
        assertNull(rt.moveTarget)
    }

    @Test
    fun `each press says it afresh, and a double tap is never one`() {
        assertEquals(9, pressOn("bar", seq = 9, dx = 100))
        assertNull(pressOn("plain", seq = 10))

        assertEquals(11, pressOn("bar", seq = 11, dx = 100))
        val r = find("bar").rect
        rt.doubleTap(r.left + 100, r.top + 2, seq = 12)
        assertNull(rt.moveTarget)
    }

    @Test
    fun `Winamp Modern moves by its title bar, not by its Play button`() {
        val wal = ZipSkinFiles(File(localResource("/WinampModern566.wal").toURI()))
        val modern = SkinRuntime(wal, TestHost(wal), FakePlayer()).apply { load() }
        // the margin a Pixel 9 Pro gives at Winamp Modern's 2x: 24 dp at 2.25 px a dp
        modern.touchSlop = 27
        val main = checkNotNull(modern.mainLayout)

        // the middle of the title bar, where the Pixel's taps landed
        modern.pointerDown(172, 7, seq = 1)
        assertEquals("the title bar", 1, modern.moveTarget)
        modern.pointerUp(172, 7)

        val play = modern.allObjects.first { it.id == "play" && it.layoutObject === main }.rect
        modern.pointerDown(play.left + play.width / 2, play.top + play.height / 2, seq = 2)
        assertNull("the Play button", modern.moveTarget)
    }
}
