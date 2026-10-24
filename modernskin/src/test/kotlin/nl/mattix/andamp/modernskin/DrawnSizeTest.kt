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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * How wide the phone counts a stack as shown ([SkinRuntime.drawnSize]): a window's room on its
 * right that shows nothing is left out, a drawer sliding out into it counts as far as it shows, and
 * so does the window's background.
 */
class DrawnSizeTest {
    // a main window 300 px wide whose shaped body ends at 200, with a drawer under the body that
    // can slide out to the right, and a playlist window to dock under it
    private val files =
        files(
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="100">""" +
                """<group id="drawer" x="100" y="10" w="100" h="80" sysregion="1" rectrgn="1"/>""" +
                """<group id="body" x="0" y="0" w="200" h="100" sysregion="1" rectrgn="1"/>""" +
                """</layout></container>""" +
                """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="300" h="50"/>""" +
                """</container>""",
        )

    private val rt = runtime(files)

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
    fun `a background that shows past the shaped body counts, as far as it shows`() {
        // a bar across the whole window in the top 20 rows, as MMD3's shade bar over its drawers
        val bar = runtime(withBackground(png(300, 100) { _, y -> y < 20 }))
        assertEquals(300, checkNotNull(bar.mainLayout).contentRight())
        assertEquals(300 to 100, bar.drawnSize)

        // a picture half as wide stretched over the window, showing in its first 120 columns
        val stretched = runtime(withBackground(png(150, 50) { x, y -> x < 120 && y == 0 }))
        assertEquals(240 to 100, stretched.drawnSize)

        // a background that ends before the body leaves the body's edge
        val short = runtime(withBackground(png(300, 100) { x, _ -> x < 150 }))
        assertEquals(200 to 100, short.drawnSize)

        // nothing of it shows
        val clear = runtime(withBackground(png(300, 100) { _, _ -> false }))
        assertEquals(200 to 100, clear.drawnSize)
    }

    @Test
    fun `MMD3's closed drawers are left out of the drawn stack, and a drawer counts while it slides out and back`() {
        val mmd3 = Mmd3()
        val main = checkNotNull(mmd3.rt.mainLayout)
        val eq = checkNotNull(main.findObject("main.eq"))

        // the body ends at 381; the EQ drawer, under it while closed, shows past it as it slides
        fun shown() = if (eq.visible) maxOf(BODY, eq.rect.right) else BODY

        mmd3.settle()
        assertEquals(583 to 216, mmd3.rt.stackSize)
        assertEquals("the body, without the room the drawers keep", BODY to 216, mmd3.rt.drawnSize)

        mmd3.rt.tapTestObject("eqtoggle")
        val out = ArrayList<Int>()
        mmd3.settle { drawn ->
            assertEquals(shown() to 216, drawn)
            out.add(drawn.first)
        }
        val open = mmd3.rt.drawnSize.first
        assertTrue("open past the body: $open", open > BODY + 100)
        assertEquals("out to the drawer's right edge", eq.rect.right, open)
        assertTrue("seen between the body and the open drawer while it slides out: $out", out.any { it in BODY + 1 until open })

        mmd3.rt.tapTestObject("eqtoggle2")
        val back = ArrayList<Int>()
        mmd3.settle { drawn ->
            assertEquals(shown() to 216, drawn)
            back.add(drawn.first)
        }
        assertTrue(
            "seen between the open drawer and the body while it slides back: $back",
            back.any { it in BODY + 1 until open },
        )
        assertEquals(BODY to 216, mmd3.rt.drawnSize)
    }

    @Test
    fun `MMD3's shade bar is drawn as wide as its background shows, past the drawers in it`() {
        val mmd3 = Mmd3()
        mmd3.settle()
        val container = checkNotNull(mmd3.rt.mainContainer)

        container.switchTo("shade")
        mmd3.settle()
        val shade = checkNotNull(mmd3.rt.mainLayout)
        assertEquals("shade", shade.id)
        assertEquals(800 to 161, shade.size)
        assertEquals("the bar's background reaches past the drawers' 677", 800, shade.contentRight())
        assertEquals(800 to 161, mmd3.rt.drawnSize)

        container.switchTo("normal")
        mmd3.settle()
        assertEquals(BODY to 216, mmd3.rt.drawnSize)
    }

    /** MMD3 loaded on a phone-sized view, with a clock that [settle] moves on. */
    private class Mmd3 {
        private var now = 0L
        private val wal = ZipSkinFiles(File(localResource("/MMD3.wal").toURI()))
        val rt =
            SkinRuntime(wal, TestHost(wal), FakePlayer().apply { clock = { now } }).apply {
                clock = { now }
                viewSize = 480 to 954
                load()
            }

        /** Four seconds of ticks, 50 ms apart, with the drawn size after each. */
        fun settle(each: (Pair<Int, Int>) -> Unit = {}) {
            repeat(80) {
                now += 50
                rt.tick()
                each(rt.drawnSize)
            }
        }
    }

    private fun runtime(files: SkinFiles) =
        SkinRuntime(files, TestHost(files), FakePlayer().apply { clock = { 0L } }).apply {
            clock = { 0L }
            viewSize = 300 to 1000
            load()
        }

    /** A main window 300 px wide whose shaped body ends at 200, over the background picture [bg]. */
    private fun withBackground(bg: ByteArray): SkinFiles =
        files(
            """<elements><bitmap id="bg" file="bg.png"/></elements>""" +
                """<container id="main" name="t" default_visible="1">""" +
                """<layout id="normal" w="300" h="100" background="bg">""" +
                """<group id="body" x="0" y="0" w="200" h="100" sysregion="1" rectrgn="1"/>""" +
                """</layout></container>""",
            "bg.png" to bg,
        )

    private fun files(
        player: String,
        vararg pictures: Pair<String, ByteArray>,
    ): SkinFiles =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>"""
                            .toByteArray(),
                    "player.xml" to player.toByteArray(),
                ) + pictures

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    /** A [w] x [h] PNG, opaque where [shows] says, clear elsewhere. */
    private fun png(
        w: Int,
        h: Int,
        shows: (Int, Int) -> Boolean,
    ): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) if (shows(x, y)) img.setRGB(x, y, OPAQUE_GRAY)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** A tap in the middle of [id], a visible object of the main window. */
    private fun SkinRuntime.tapTestObject(id: String) {
        val o: GuiObject = checkNotNull(checkNotNull(mainLayout).findObject(id)?.takeIf { it.visible }) { id }
        val x = o.rect.left + o.rect.width / 2
        val y = o.rect.top + o.rect.height / 2
        pointerDown(x, y)
        pointerUp(x, y)
    }

    private companion object {
        /** Where MMD3's main window shows on the right with its drawers closed. */
        const val BODY = 381

        const val OPAQUE_GRAY = 0xFF808080.toInt()
    }
}
