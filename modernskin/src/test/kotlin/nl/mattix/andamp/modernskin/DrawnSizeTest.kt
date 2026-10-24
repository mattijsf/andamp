// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How wide the phone counts a stack as shown ([SkinRuntime.drawnSize]): a window's room on its
 * right that shows nothing is left out, and a drawer sliding out into it counts as far as it shows.
 */
class DrawnSizeTest {
    // a main window 300 px wide whose shaped body ends at 200, with a drawer under the body that
    // can slide out to the right, and a playlist window to dock under it
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="100">""" +
                        """<group id="drawer" x="100" y="10" w="100" h="80" sysregion="1" rectrgn="1"/>""" +
                        """<group id="body" x="0" y="0" w="200" h="100" sysregion="1" rectrgn="1"/>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="300" h="50"/>""" +
                        """</container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt =
        SkinRuntime(files, TestHost(files), FakePlayer().apply { clock = { 0L } }).apply {
            clock = { 0L }
            viewSize = 300 to 1000
            load()
        }

    private val main get() = checkNotNull(rt.mainLayout)

    @Test
    fun `a window's room on its right that shows nothing is left out of the drawn stack, though the window keeps its size`() {
        assertEquals(300 to 100, main.size)
        assertEquals(200, main.contentRight())
        assertEquals(300 to 100, rt.stackSize)
        assertEquals(200 to 100, rt.drawnSize)
    }

    @Test
    fun `a drawer sliding out into that room counts as far as it shows`() {
        val drawer = checkNotNull(main.findObject("drawer"))

        drawer.setXmlParam("x", "150")
        assertEquals(250 to 100, rt.drawnSize)

        drawer.setXmlParam("x", "200")
        assertEquals(300 to 100, rt.drawnSize)

        drawer.setXmlParam("visible", "0")
        assertEquals(200 to 100, rt.drawnSize)
    }

    @Test
    fun `a window docked under the main one is as wide as what shows of it, and so is the drawn stack`() {
        rt.openWindow(checkNotNull(rt.container("pl")))
        rt.ensureLayout()

        assertEquals(200, rt.windowRect(rt.windows[1]).width)
        assertEquals(300 to 150, rt.stackSize)
        assertEquals(200 to 150, rt.drawnSize)
    }

    @Test
    fun `MMD3's closed drawers are left out of the drawn stack, and a drawer counts while it slides out and back`() {
        val wal = ZipSkinFiles(File(localResource("/MMD3.wal").toURI()))
        var now = 0L
        val rt =
            SkinRuntime(wal, TestHost(wal), FakePlayer().apply { clock = { now } }).apply {
                clock = { now }
                viewSize = 480 to 954
                load()
            }
        val main = checkNotNull(rt.mainLayout)
        val eq = checkNotNull(main.findObject("main.eq"))

        fun settle(each: (Pair<Int, Int>) -> Unit = {}) {
            repeat(80) {
                now += 50
                rt.tick()
                each(rt.drawnSize)
            }
        }
        settle()
        assertEquals(583 to 216, rt.stackSize)
        assertEquals("the body, without the room the drawers keep", 381 to 216, rt.drawnSize)

        rt.tapTestObject("eqtoggle")
        settle { drawn ->
            // the drawer shows as it slides: the drawn stack follows it to the right
            assertTrue("$drawn", drawn.first in 381..583)
            assertEquals(main.contentRight(), drawn.first)
        }
        assertEquals("out to the drawer's right edge", eq.rect.right to 216, rt.drawnSize)

        rt.tapTestObject("eqtoggle2")
        settle()
        assertEquals(381 to 216, rt.drawnSize)
    }

    /** A tap in the middle of [id], a visible object of the main window. */
    private fun SkinRuntime.tapTestObject(id: String) {
        val o: GuiObject = checkNotNull(checkNotNull(mainLayout).findObject(id)?.takeIf { it.visible }) { id }
        val x = o.rect.left + o.rect.width / 2
        val y = o.rect.top + o.rect.height / 2
        pointerDown(x, y)
        pointerUp(x, y)
    }
}
