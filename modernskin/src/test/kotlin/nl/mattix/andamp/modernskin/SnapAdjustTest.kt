// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
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
 * A window's snap margins (`snapadjust*`, a script's snapAdjust): the part of a window Winamp docks
 * and snaps to, and so the part of the stack the phone counts as shown.
 */
class SnapAdjustTest {
    // a main window 100 px high whose shaped body ends at 60, with a 40 px snap margin under it,
    // and a playlist window to dock under it
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1">""" +
                        """<layout id="normal" w="200" h="100" snapadjustbottom="40">""" +
                        """<group id="body" x="0" y="0" w="200" h="60" sysregion="1"/>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="200" h="50"/>""" +
                        """</container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt =
        SkinRuntime(files, TestHost(files), FakePlayer().apply { clock = { 0L } }).apply {
            clock = { 0L }
            viewSize = 200 to 1000
            load()
        }

    private val main get() = checkNotNull(rt.mainLayout)

    private fun snap(name: String) = main.makiCall("getsnapadjust$name", emptyList())!!.asInt

    @Test
    fun `the strip a snap margin leaves out is not part of the drawn stack, though the window keeps its size`() {
        assertEquals(listOf(0, 0, 0, 40), listOf("left", "top", "right", "bottom").map(::snap))
        assertEquals(200 to 100, main.size)
        assertEquals(200 to 60, main.snappedSize())
        assertEquals(200 to 100, rt.stackSize)
        assertEquals(200 to 60, rt.drawnSize)
    }

    @Test
    fun `a script's snapAdjust sets the margins the drawn stack goes by`() {
        main.makiCall("snapadjust", listOf(Value.Int(1), Value.Int(2), Value.Int(3), Value.Int(0)))

        assertEquals(listOf(1, 2, 3, 0), listOf("left", "top", "right", "bottom").map(::snap))
        // a right margin over the shaped body, which reaches the right edge, leaves the width
        assertEquals(200 to 100, rt.drawnSize)
    }

    @Test
    fun `a window docked under the main one keeps its place and ends the drawn stack`() {
        rt.toggleTestWindow("pl")

        assertEquals("docked where the main window's body ends", 60, rt.windowRect(rt.windows[1]).top)
        assertEquals(200 to 110, rt.stackSize)
        assertEquals(200 to 110, rt.drawnSize)
    }

    @Test
    fun `what shows inside the margin still counts, as a drawer sliding out over it`() {
        val body = checkNotNull(main.findObject("body"))
        body.setXmlParam("h", "90")

        assertEquals(200 to 90, rt.drawnSize)
    }

    @Test
    fun `Winamp Modern's closed drawer is left out of the drawn stack, and the open drawer is in it`() {
        val wal = ZipSkinFiles(File(localResource("/WinampModern566.wal").toURI()))
        var now = 0L
        val rt =
            SkinRuntime(wal, TestHost(wal), FakePlayer().apply { clock = { now } }).apply {
                clock = { now }
                viewSize = 360 to 1200
                load()
            }
        val main = checkNotNull(rt.mainLayout)

        fun settle(each: (Pair<Int, Int>) -> Unit = {}) {
            repeat(80) {
                now += 50
                rt.tick()
                each(rt.drawnSize)
            }
        }
        settle()
        assertEquals(116, main.snapBottom)
        assertEquals(354 to 280, rt.stackSize)
        assertEquals(354 to 164, rt.drawnSize)

        rt.tapTestObject(main, "drawer.button.open")
        settle { drawn ->
            // the drawer shows as it slides: the drawn stack follows it down
            assertTrue("$drawn", drawn.second in 164..280)
            assertEquals(main.contentBottom(), drawn.second)
        }
        assertEquals(0, main.snapBottom)
        assertEquals(354 to 280, rt.drawnSize)

        rt.tapTestObject(main, "drawer.button.close")
        settle()
        assertEquals(116, main.snapBottom)
        assertEquals(354 to 164, rt.drawnSize)
    }

    /** Opens the window [id] as its button would. */
    private fun SkinRuntime.toggleTestWindow(id: String) {
        val c = checkNotNull(containers.firstOrNull { it.id == id })
        openWindow(c)
        ensureLayout()
    }

    /** A tap in the middle of [id] in [window]. */
    private fun SkinRuntime.tapTestObject(
        window: nl.mattix.andamp.modernskin.skin.Layout,
        id: String,
    ) {
        val o: GuiObject = checkNotNull(window.findObject(id)) { id }
        val origin = windowRect(window)
        val x = origin.left + o.rect.left + o.rect.width / 2
        val y = origin.top + o.rect.top + o.rect.height / 2
        pointerDown(x, y)
        pointerUp(x, y)
    }
}
