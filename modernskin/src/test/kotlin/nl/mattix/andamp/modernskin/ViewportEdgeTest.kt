// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.Container
import nl.mattix.andamp.modernskin.skin.Edge
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Layout
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Where a window sits in the viewport as scripts read and move it: the edges of the viewport a
 * script moved it against, as a phone puts the stack against the same edges of its box; the middle
 * for a main window moved against none, so a script that puts it back where it read it moves it
 * against none; and a place for each layout.
 */
class ViewportEdgeTest {
    // a 246 by 228 main window, as CornerAmp's, with a 275 by 14 shade bar; and a playlist
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="246" h="228"/>""" +
                        """<layout id="shade" w="275" h="14"/></container>""" +
                        """<container id="pl" name="Playlist" default_y="250" default_visible="0">""" +
                        """<layout id="normal" w="275" h="116"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    /** What the host answers for a skin menu. */
    private var answer = 0

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

            override fun chooseFromMenu(menu: SkinMenu): Int = answer
        }

    private val rt = SkinRuntime(files, host, FakePlayer()).apply { load() }
    private val window: Container get() = checkNotNull(rt.mainContainer)
    private val main: Layout get() = checkNotNull(window.layout("normal"))
    private val shade: Layout get() = checkNotNull(window.layout("shade"))

    private val Layout.edges get() = edgeX to edgeY
    private val Layout.place get() = viewX to viewY

    /** A script's resize of [layout] to [x], [y] at the size it has. */
    private fun resize(
        x: Int,
        y: Int,
        layout: Layout = main,
    ) {
        val (w, h) = layout.size
        layout.makiCall("resize", listOf(Value.Int(x), Value.Int(y), Value.Int(w), Value.Int(h)))
    }

    /** Where a script reads [layout]'s place: getLeft and getTop. */
    private fun read(layout: Layout = main) =
        checkNotNull(layout.makiCall("getleft", emptyList())).asInt to checkNotNull(layout.makiCall("gettop", emptyList())).asInt

    @Test
    fun `a main window no script moved sits in the middle of the viewport, against no edge`() {
        assertEquals("the screen before the phone gives a view", (1080 - 246) / 2 to (1920 - 228) / 2, read())
        rt.viewSize = 320 to 633
        assertEquals((320 - 246) / 2 to (633 - 228) / 2, read())
        assertEquals("a shade bar too", (320 - 275) / 2 to (633 - 14) / 2, read(shade))
        assertEquals(null to null, main.edges)
        assertEquals(null to null, shade.edges)
    }

    @Test
    fun `a window moved into a corner of the viewport sits against that corner's edges`() {
        rt.viewSize = 320 to 633

        resize(0, 0)
        assertEquals("left up", Edge.START to Edge.START, main.edges)
        resize(320 - 246, 0)
        assertEquals("right up", Edge.END to Edge.START, main.edges)
        resize(0, 633 - 228)
        assertEquals("left down", Edge.START to Edge.END, main.edges)
        resize(320 - 246, 633 - 228)
        assertEquals("right down", Edge.END to Edge.END, main.edges)
        resize(30, 633 - 228)
        assertEquals("away from the side edges", null to Edge.END, main.edges)
        resize(30, 40)
        assertEquals("against neither", null to null, main.edges)
    }

    @Test
    fun `a window moved into a corner before the phone gives a viewport stays in that corner of it`() {
        // the screen a skin sees before the host sets its view: 1080 by 1920
        resize(1080 - 246, 1920 - 228)
        assertEquals(Edge.END to Edge.END, main.edges)

        rt.viewSize = 320 to 633
        assertEquals("where a script reads it", 320 - 246 to 633 - 228, read())
        assertEquals(320 to 633, rt.viewport)

        // the phone turned
        rt.viewSize = 714 to 290
        assertEquals(714 - 246 to 290 - 228, read())
        assertEquals(Edge.END to Edge.END, main.edges)
    }

    @Test
    fun `a script that moves the main window back to the place it read moves it against no edge`() {
        // views with room around the window, and views about as large as it, where the middle would touch an edge
        for (view in listOf(320 to 633, 714 to 290, 246 to 228, 247 to 229, 200 to 100)) {
            rt.viewSize = view
            val was = read()
            resize(0, 0)
            resize(was.first, was.second)
            assertEquals("$view", null to null, main.edges)
            assertEquals("$view", was, read())
        }
    }

    @Test
    fun `a main window moved against no edge goes to the middle of a new viewport, another window stays where it was`() {
        val playlist = checkNotNull(rt.container("pl")?.current)
        assertEquals(0 to 250, playlist.place)
        resize(30, 40)
        resize(31, 41, playlist)

        rt.viewSize = 320 to 633
        assertEquals((320 - 246) / 2 to (633 - 228) / 2, read())
        assertEquals(31 to 41, read(playlist))
    }

    @Test
    fun `each layout keeps a separate place and separate edges`() {
        rt.viewSize = 320 to 633
        val middle = read()
        window.switchTo("shade")
        resize(0, 0, shade)
        assertEquals(Edge.START to Edge.START, shade.edges)
        assertEquals("the main window where it was", middle, read(main))
        assertEquals(null to null, main.edges)

        window.switchTo("normal")
        assertEquals(null to null, checkNotNull(rt.mainLayout).edges)
        assertEquals("the shade bar where it was put", 0 to 0, read(shade))
    }

    @Test
    fun `moving the window through its container moves the layout it shows`() {
        rt.viewSize = 320 to 633
        resize(320 - 246, 633 - 228)

        window.makiCall("setlocation", listOf(Value.Int(50), Value.Int(633 - 228)))
        assertEquals(null to Edge.END, main.edges)
        window.makiCall("setxmlparam", listOf(Value.Str("x"), Value.Str("0")))
        assertEquals(Edge.START to Edge.END, main.edges)
        window.makiCall("setxmlparam", listOf(Value.Str("y"), Value.Str("12")))
        assertEquals(Edge.START to null, main.edges)
        assertEquals(0 to 12, read())
        assertEquals(null to null, shade.edges)
    }

    @Test
    fun `the phone sizing a window to its box moves it against no edge`() {
        rt.viewSize = 320 to 633
        val was = read()
        main.resizeTo(320, 633)
        assertEquals(320 to 633, main.size)
        assertEquals(null to null, main.edges)
        assertEquals(was, read())
    }

    /** CornerAmp, with the host answering its menus with [answer], in a 320 by 633 view. */
    private fun cornerAmp(stored: Int? = null): SkinRuntime {
        val wal = ZipSkinFiles(File(localResource("/CornerAmp_Redux.wal").toURI()))
        val corner =
            object : SkinHost by TestHost(wal) {
                override fun chooseFromMenu(menu: SkinMenu): Int = answer
            }
        return SkinRuntime(wal, corner, FakePlayer()).apply {
            stored?.let { privateInts["corner/lastpos"] = it }
            load()
            viewSize = 320 to 633
        }
    }

    @Test
    fun `CornerAmp's corner menu puts its player against the edges of the corner picked`() {
        val corner = cornerAmp()
        val player = checkNotNull(corner.mainLayout)
        val expected =
            mapOf(
                1 to (Edge.START to Edge.START),
                2 to (Edge.END to Edge.START),
                3 to (Edge.START to Edge.END),
                4 to (Edge.END to Edge.END),
            )
        for ((seq, picked) in listOf(4, 2, 3, 1, 4).withIndex()) {
            answer = picked
            // a long press on the title it shows now opens its menu
            val title = player.children.first { it.id.startsWith("title") && it.visible }
            corner.pointerDown(title.rect.left + 3, title.rect.top + 3, seq)
            corner.rightClick(seq)
            corner.pointerCancel()
            assertEquals("corner $picked", expected[picked], player.edges)
        }
        assertEquals("right down, in the view the phone gave", 320 - 246 to 633 - 228, player.place)
    }

    @Test
    fun `CornerAmp starts in the corner it was left in`() {
        val player = checkNotNull(cornerAmp(stored = 4).mainLayout)

        assertEquals(Edge.END to Edge.END, player.edges)
        assertEquals(320 - 246 to 633 - 228, player.place)
    }

    @Test
    fun `MMD3 comes back from its shade bars against no edge, and puts each bar where it puts it the first time`() {
        val wal = ZipSkinFiles(File(localResource("/MMD3.wal").toURI()))
        val mmd3 = SkinRuntime(wal, TestHost(wal), FakePlayer()).apply { load() }
        val window = checkNotNull(mmd3.mainContainer)
        // its view sideways in the app, and upright
        for (view in listOf(714 to 290, 480 to 950)) {
            mmd3.viewSize = view
            repeat(2) { round ->
                val name = "$view, round $round"
                // a bar 16 px in from the left of the screen at its top, and the other in the top left corner
                window.switchTo("shade")
                assertEquals(name, null to Edge.START, checkNotNull(mmd3.mainLayout).edges)
                window.switchTo("shade2")
                assertEquals(name, Edge.START to Edge.START, checkNotNull(mmd3.mainLayout).edges)
                window.switchTo("normal")
                assertEquals(name, null to null, checkNotNull(mmd3.mainLayout).edges)
            }
        }
        // the phone turns while a bar shows
        window.switchTo("shade")
        mmd3.viewSize = 714 to 290
        window.switchTo("normal")
        assertEquals(null to null, checkNotNull(mmd3.mainLayout).edges)
        window.switchTo("shade")
        assertEquals(null to Edge.START, checkNotNull(mmd3.mainLayout).edges)
    }
}
