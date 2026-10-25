// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.compose.ui.unit.IntRect
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Layout
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a modern skin is fitted into the player's box: sideways in the app, two columns when they
 * show the skin larger than one, decided again when a window opens or closes, from the windows'
 * natural sizes, with the right column clear of the left one and as wide whatever a drawer does;
 * and upright, a drawer that runs past the screen shrinks the skin once its slide ends, and a
 * docked window wider than the main one shows whole.
 *
 * The skins are small stand-ins with the corpus skins' sizes. The boxes are the ones the `placed`
 * lines report: andamp-p4-small (720x1280 at 320 dpi) has 720x1066 upright and 1138x568 sideways;
 * 1184x672 is a small phone with a taller box; the Pixel 9 Pro has about 2142x870 sideways.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalFittingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun skin(player: String): SkinFiles =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to player,
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    /** A playlist window [w] by [h] that can be made taller, at least [minW] wide. */
    private fun playlist(
        w: Int,
        h: Int,
        minW: Int = w,
    ) = """<container id="pl" name="Playlist" default_visible="0">""" +
        """<layout id="normal" w="$w" h="$h" minimum_w="$minW" minimum_h="$h">""" +
        """<component id="list" param="guid:{45F3F7C1-A6F3-4ee6-A15E-125E92FC3F8D}" x="0" y="0" w="${w - 10}" h="${h - 10}"/>""" +
        """<layer id="pl.sizer" resize="bottomright" x="${w - 10}" y="${h - 10}" w="10" h="10"/>""" +
        """</layout></container>"""

    // Winamp Modern: a 354 by 280 main window of which 164 px show while its drawer is closed, and a
    // playlist 164 high
    private val winampModern =
        skin(
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="354" h="280">""" +
                """<group id="body" x="0" y="0" w="354" h="164" sysregion="1" rectrgn="1"/>""" +
                """</layout></container>""" + playlist(354, 164),
        )

    // CornerAmp: a 246 by 228 main window, an equalizer held at 275 by 145 and a playlist at least
    // 275 wide
    private val cornerAmp =
        skin(
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="246" h="228"/></container>""" +
                """<container id="eq" name="Equalizer" default_visible="0"><layout id="normal" w="275" h="145" """ +
                """minimum_w="275" minimum_h="145" maximum_w="275" maximum_h="145"/></container>""" +
                playlist(320, 200, minW = 275),
        )

    // DeClassified: a 275 by 116 main window and a playlist as high
    private val deClassified =
        skin(
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="275" h="116"/></container>""" +
                playlist(275, 116),
        )

    // MMD3: a 583 px wide main window whose body ends at 381, with a drawer that slides out right of
    // it, and a shade bar 800 px wide
    private val mmd3 =
        skin(
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="583" h="216">""" +
                """<group id="drawer" x="181" y="20" w="200" h="150" sysregion="1" rectrgn="1"/>""" +
                """<group id="body" x="0" y="0" w="381" h="216" sysregion="1" rectrgn="1"/>""" +
                """</layout><layout id="shade" w="800" h="23"/></container>""" + playlist(381, 260, minW = 275),
        )

    private val small = WalFit(1138, 568, 2f, sideways = true)
    private val taller = WalFit(1184, 672, 2f, sideways = true)
    private val pixel = WalFit(2142, 870, 2.25f, sideways = true)

    /** A skin fitted as an engine fits it: loaded, given the box, and laid out once. */
    private inner class Fitted(
        files: SkinFiles,
        var fit: WalFit,
        player: FakePlayer = FakePlayer(),
    ) {
        val rt = SkinRuntime(files, WalHost(files, app.cacheDir), player).apply { load() }
        val fitting = WalFitting()
        val baseW = WalFitting.baseWOf(rt)

        init {
            fitting.apply(rt, fit, baseW)
            rt.ensureLayout()
        }

        fun refit(to: WalFit = fit) {
            if (to != fit) {
                fit = to
                fitting.apply(rt, to, baseW)
            }
            fitting.refit(rt, fit, baseW)
            rt.ensureLayout()
        }

        fun open(id: String) {
            rt.openWindow(checkNotNull(rt.container(id)))
            refit()
        }

        fun close(id: String) {
            rt.closeWindow(checkNotNull(rt.container(id)))
            refit()
        }

        fun window(id: String): Layout = checkNotNull(rt.container(id)?.current)

        fun layout(): WalLayout = fitting.layout(rt, fit, baseW, rt.stackSize, rt.drawnSize, rt.topWindow != null, true)
    }

    @Test
    fun `two columns are used only where they show the skin larger`() {
        val modern = Fitted(winampModern, taller).apply { open("pl") }
        assertEquals("Winamp Modern stacked: 2x, against 1.67x in two columns", 1, modern.rt.columns)
        assertEquals(2f, modern.layout().scale)
        modern.refit(small)
        assertEquals("1.73x against 1.61x", 1, modern.rt.columns)

        val corner = Fitted(cornerAmp, taller).apply { open("pl") }
        assertEquals("CornerAmp: 2x in two columns, against 1.57x stacked", 2, corner.rt.columns)
        assertEquals(2f, corner.layout().scale)
        corner.refit(small)
        assertEquals("2x against 1.33x", 2, corner.rt.columns)
        assertEquals(2f, corner.layout().scale)

        val tie = Fitted(deClassified, taller).apply { open("pl") }
        assertEquals("DeClassified: 2x either way, so it stays stacked", 1, tie.rt.columns)

        val mmd3 = Fitted(mmd3, small).apply { open("pl") }
        assertEquals("MMD3 on a small phone: 1.19x stacked, against 1.18x", 1, mmd3.rt.columns)
        mmd3.refit(pixel)
        assertEquals("on the Pixel: 1.83x stacked, against 2x", 2, mmd3.rt.columns)
        assertEquals(2f, mmd3.layout().scale)
    }

    @Test
    fun `Winamp Modern goes to two columns where the box is wide enough for them`() {
        val modern = Fitted(winampModern, pixel).apply { open("pl") }

        assertEquals("3x in two columns, against 2x stacked", 2, modern.rt.columns)
        assertEquals(3f, modern.layout().scale)
        assertEquals(
            "the playlist right of the main window, as tall as the box",
            Rect(354, 0, 354, 870 / 3),
            modern.rt.windowRect(modern.window("pl")),
        )
    }

    @Test
    fun `opening or closing a list window sideways decides again`() {
        val corner = Fitted(cornerAmp, taller)
        assertEquals(1, corner.rt.columns)
        assertEquals(2f, corner.layout().scale)

        corner.open("eq")
        assertEquals("no list window, no second column", 1, corner.rt.columns)
        assertNull(corner.layout().dock)

        corner.open("pl")
        assertEquals(2, corner.rt.columns)
        assertEquals(
            "the equalizer under the main window, at the 275 px it is held at",
            Rect(0, 228, 275, 145),
            corner.rt.windowRect(corner.window("eq")),
        )
        // 1.8x in two columns (550 by 373), against 1.17x stacked
        assertEquals(672f / 373, corner.layout().scale)
        assertEquals(
            "the playlist from the top where the equalizer ends, as tall as the box",
            Rect(275, 0, 275, 373),
            corner.rt.windowRect(corner.window("pl")),
        )
        assertTrue(
            "the columns do not overlap",
            corner.rt.windowRect(corner.window("pl")).left >= corner.rt.windowRect(corner.window("eq")).right,
        )
        assertEquals("the visualizer docks under the left column", IntRect(0, 0, 275, 373), corner.layout().dock)

        corner.close("pl")
        assertEquals(1, corner.rt.columns)
        assertEquals(Rect(0, 228, 275, 145), corner.rt.windowRect(corner.window("eq")))
        assertNull(corner.layout().dock)
    }

    @Test
    fun `sideways, a drawer sliding out of the main window leaves the columns and the scale as they are`() {
        val skin = Fitted(mmd3, pixel).apply { open("pl") }
        val drawer = checkNotNull(skin.window("main").findObject("drawer"))
        assertEquals(2, skin.rt.columns)
        assertEquals(2f, skin.layout().scale)
        val playlist = Rect(583, 0, 381, 870 / 2)
        assertEquals(
            "right of the main window's full width, as wide as its body",
            playlist,
            skin.rt.windowRect(skin.window("pl")),
        )

        skin.rt.moving.add(drawer)
        drawer.setXmlParam("x", "383")
        assertEquals("while it slides", 2f, skin.layout().scale)
        skin.rt.moving.clear()
        assertEquals("once it rests", 2f, skin.layout().scale)
        assertEquals("the playlist keeps its width", playlist, skin.rt.windowRect(skin.window("pl")))

        // turned sideways or the playlist opened while the drawer is out: the same columns and scale
        skin.close("pl")
        skin.open("pl")
        assertEquals(2, skin.rt.columns)
        assertEquals(2f, skin.layout().scale)
        drawer.setXmlParam("x", "181")
        assertEquals("closed again", 2f, skin.layout().scale)
        assertEquals(playlist, skin.rt.windowRect(skin.window("pl")))
    }

    @Test
    fun `a tap on a row in the right column selects it, a second tap or a double tap plays it`() {
        val player = FakePlayer().apply { playlist = List(5) { Track("Track $it", 60_000) } }
        val modern = Fitted(winampModern, pixel, player).apply { open("pl") }
        assertEquals(2, modern.rt.columns)
        val list = modern.rt.windowRect(modern.window("pl"))

        // rows are 15 px from 3 px under the list's top, and the list sits at the window's corner
        fun row(i: Int) = list.left + 40 to list.top + 3 + i * 15 + 7

        fun tap(at: Pair<Int, Int>) {
            modern.rt.pointerDown(at.first, at.second)
            modern.rt.pointerUp(at.first, at.second)
        }
        tap(row(1))
        assertEquals("selected only", Player.Status.STOPPED, player.status)
        tap(row(1))
        assertEquals(Player.Status.PLAYING, player.status)
        assertEquals(1, player.trackIndex)

        tap(row(3))
        modern.rt.doubleTap(row(3).first, row(3).second)
        assertEquals(3, player.trackIndex)
    }

    @Test
    fun `the floating player keeps the fitted stack sideways`() {
        val corner = Fitted(cornerAmp, taller.copy(compact = true)).apply { open("pl") }

        assertEquals(1, corner.rt.columns)
        assertEquals(
            "docked under the main window at its natural height",
            Rect(0, 228, 275, 200),
            corner.rt.windowRect(corner.window("pl")),
        )
        assertEquals("the whole stack fits the box", 672f / 428, corner.layout().scale)
    }

    @Test
    fun `fitting the same box twice keeps the scale`() {
        val corner = Fitted(cornerAmp, taller).apply { open("pl") }
        val first = corner.layout()
        val view = corner.rt.viewSize

        corner.fitting.apply(corner.rt, corner.fit, corner.baseW)
        corner.refit()
        assertEquals(first, corner.layout())
        assertEquals(view, corner.rt.viewSize)

        val mmd3 = Fitted(mmd3, pixel).apply { open("pl") }
        val scale = mmd3.layout().scale
        repeat(2) { mmd3.fitting.apply(mmd3.rt, mmd3.fit, mmd3.baseW) }
        mmd3.refit()
        assertEquals(scale, mmd3.layout().scale)
    }

    @Test
    fun `deciding the columns sends the skin no resize events`() {
        val corner = Fitted(cornerAmp, taller)
        corner.rt.openWindow(checkNotNull(corner.rt.container("pl")))
        val passes = corner.rt.layoutPasses

        corner.fitting.refit(corner.rt, corner.fit, corner.baseW)
        assertEquals(2, corner.rt.columns)
        assertEquals("no layout pass, so no window heard of a size it does not get", passes, corner.rt.layoutPasses)
        corner.rt.ensureLayout()
        assertEquals("one pass sets the playlist where the decision put it", passes + 1, corner.rt.layoutPasses)

        corner.fitting.refit(corner.rt, corner.fit, corner.baseW)
        corner.rt.ensureLayout()
        assertEquals("the same windows: nothing is decided again", passes + 1, corner.rt.layoutPasses)
    }

    @Test
    fun `upright, a drawer that runs past the screen shrinks the skin once its slide ends, and grows back after`() {
        val upright = WalFit(720, 1066, 2f, sideways = false)
        val skin = Fitted(mmd3, upright)
        val drawer = checkNotNull(skin.window("main").findObject("drawer"))
        assertEquals(720f / 381, skin.layout().scale)

        skin.rt.moving.add(drawer)
        drawer.setXmlParam("x", "383")
        assertEquals("while it slides the scale holds", 720f / 381, skin.layout().scale)
        skin.rt.moving.clear()
        assertEquals("once it rests the whole drawer fits", 720f / 583, skin.layout().scale)
        assertEquals(0f, skin.layout().left)

        skin.rt.moving.add(drawer)
        drawer.setXmlParam("x", "181")
        assertEquals("closing, the scale holds", 720f / 583, skin.layout().scale)
        skin.rt.moving.clear()
        assertEquals("closed, it grows back", 720f / 381, skin.layout().scale)
    }

    @Test
    fun `upright, a window opened while the drawer is out still lets the skin grow back when it closes`() {
        val upright = WalFit(720, 1066, 2f, sideways = false)
        val skin = Fitted(mmd3, upright)
        val drawer = checkNotNull(skin.window("main").findObject("drawer"))
        drawer.setXmlParam("x", "383")
        assertEquals("the drawer out", 720f / 583, skin.layout().scale)

        skin.open("pl")
        assertEquals("the view the closed drawer gives", 381 to 564, skin.rt.viewSize)
        assertEquals(720f / 583, skin.layout().scale)

        drawer.setXmlParam("x", "181")
        assertEquals("closed, it grows back", 720f / 381, skin.layout().scale)
        assertEquals(
            "and the playlist reaches the bottom",
            Rect(0, 216, 381, 564 - 216),
            skin.rt.windowRect(skin.window("pl")),
        )
    }

    @Test
    fun `upright, a playlist under a shade bar wider than the main window still reaches the bottom`() {
        val upright = WalFit(720, 1066, 2f, sideways = false)
        val skin = Fitted(mmd3, upright).apply { open("pl") }

        checkNotNull(skin.rt.container("main")).switchTo("shade")
        skin.refit()
        assertEquals("800 px across at 0.9x", 720f / 800, skin.layout().scale)
        assertEquals(800 to 1184, skin.rt.viewSize)
        assertEquals(Rect(0, 23, 800, 1184 - 23), skin.rt.windowRect(skin.window("pl")))

        checkNotNull(skin.rt.container("main")).switchTo("normal")
        skin.refit()
        assertEquals("back at the main window's scale", 720f / 381, skin.layout().scale)
        assertEquals(Rect(0, 216, 381, 564 - 216), skin.rt.windowRect(skin.window("pl")))
    }

    @Test
    fun `upright, a docked window wider than the main one shows whole, and the playlist still reaches the bottom`() {
        // andamp-sideways upright: 4x for CornerAmp's 246 px, but its playlist is at least 275 wide
        val upright = WalFit(1080, 2187, 2.625f, sideways = false)
        val corner = Fitted(cornerAmp, upright)
        assertEquals(4f, corner.layout().scale)

        corner.open("pl")
        assertEquals("1100 px would run past the 1080 px box: 3x", 3f, corner.layout().scale)
        assertEquals(Rect(0, 228, 275, 2187 / 3 - 228), corner.rt.windowRect(corner.window("pl")))
        assertTrue("the playlist ends inside the box", corner.layout().left + 275 * 3 <= 1080f)

        corner.close("pl")
        assertEquals(4f, corner.layout().scale)
    }
}
