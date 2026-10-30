// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.fixtures.beginSlideAsScript
import nl.mattix.andamp.modernskin.fixtures.endSlidesAsScript
import nl.mattix.andamp.modernskin.fixtures.placeAsScript
import nl.mattix.andamp.modernskin.fixtures.resizeAsScript
import nl.mattix.andamp.modernskin.fixtures.setParamAsScript
import nl.mattix.andamp.modernskin.fixtures.switchLayoutAsScript
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Track
import nl.mattix.andamp.modernskin.skin.WindowId
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
 * upright, a drawer that runs past the screen shrinks the skin once its slide ends, and a docked
 * window wider than the main one shows whole; and a main window a script moved into a corner of
 * the viewport puts the stack in that corner of the box.
 *
 * The skins are small stand-ins with the corpus skins' sizes. The boxes are the ones the `placed`
 * lines report: andamp-p4-small (720x1280 at 320 dpi) has 720x1066 upright and 1138x568 sideways;
 * 1184x672 is a small phone with a taller box; the Pixel 9 Pro has about 2142x870 sideways and
 * 960x1900 upright.
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

    // Bento: a main window that resizes both ways, at least 633 by 492, which the box sizes, and a
    // shade bar 633 by 20
    private val bento =
        skin(
            """<container id="main" name="t" default_visible="1">""" +
                """<layout id="normal" w="800" h="600" minimum_w="633" minimum_h="492">""" +
                """<layer id="sizer" resize="bottomright" x="790" y="590" w="10" h="10"/>""" +
                """</layout><layout id="shade" w="633" h="20" minimum_h="20" maximum_h="20"/></container>""",
        )

    private val small = WalFit(1138, 568, 2f, sideways = true)
    private val taller = WalFit(1184, 672, 2f, sideways = true)
    private val pixel = WalFit(2142, 870, 2.25f, sideways = true)
    private val upright = WalFit(960, 1900, 2.25f, sideways = false)

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
            check(rt.openWindow(id))
            refit()
        }

        fun close(id: String) {
            check(rt.closeWindow(id))
            refit()
        }

        fun window(id: String): WindowId = checkNotNull(rt.windowOf(id))

        fun layout(): WalLayout = fitting.layout(rt, fit, baseW, rt.stackSize, rt.drawnSize, rt.topWindow != null, true)

        /** Moves the main window to [x], [y] at the size it has, as a script's resize does. */
        fun moveMain(
            x: Int,
            y: Int,
        ) {
            val (w, h) = rt.windowSize(window("main"))
            rt.resizeAsScript(window("main"), x, y, w, h)
            rt.ensureLayout()
        }

        /** Moves the main window into a corner of the viewport the box gave the skin: the right one or the left, down or up. */
        fun moveMain(
            right: Boolean,
            down: Boolean,
        ) {
            val (w, h) = rt.windowSize(window("main"))
            val (vw, vh) = rt.viewport
            moveMain(if (right) vw - w else 0, if (down) vh - h else 0)
        }
    }

    @Test
    fun `two columns are used only where they show the skin larger`() {
        val modern = Fitted(winampModern, taller).apply { open("pl") }
        assertEquals("Winamp Modern stacked: 2x, against 1.67x in two columns", 1, modern.rt.presentation.columns)
        assertEquals(2f, modern.layout().scale)
        modern.refit(small)
        assertEquals("1.73x against 1.61x", 1, modern.rt.presentation.columns)

        val corner = Fitted(cornerAmp, taller).apply { open("pl") }
        assertEquals("CornerAmp: 2x in two columns, against 1.57x stacked", 2, corner.rt.presentation.columns)
        assertEquals(2f, corner.layout().scale)
        corner.refit(small)
        assertEquals("2x against 1.33x", 2, corner.rt.presentation.columns)
        assertEquals(2f, corner.layout().scale)

        val tie = Fitted(deClassified, taller).apply { open("pl") }
        assertEquals("DeClassified: 2x either way, so it stays stacked", 1, tie.rt.presentation.columns)

        val mmd3 = Fitted(mmd3, small).apply { open("pl") }
        assertEquals("MMD3 on a small phone: 1.19x stacked, against 1.18x", 1, mmd3.rt.presentation.columns)
        mmd3.refit(pixel)
        assertEquals("on the Pixel: 1.83x stacked, against 2x", 2, mmd3.rt.presentation.columns)
        assertEquals(2f, mmd3.layout().scale)
    }

    @Test
    fun `Winamp Modern goes to two columns where the box is wide enough for them`() {
        val modern = Fitted(winampModern, pixel).apply { open("pl") }

        assertEquals("3x in two columns, against 2x stacked", 2, modern.rt.presentation.columns)
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
        assertEquals(1, corner.rt.presentation.columns)
        assertEquals(2f, corner.layout().scale)

        corner.open("eq")
        assertEquals("no list window, no second column", 1, corner.rt.presentation.columns)
        assertNull(corner.layout().dock)

        corner.open("pl")
        assertEquals(2, corner.rt.presentation.columns)
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
        assertEquals(1, corner.rt.presentation.columns)
        assertEquals(Rect(0, 228, 275, 145), corner.rt.windowRect(corner.window("eq")))
        assertNull(corner.layout().dock)
    }

    @Test
    fun `sideways, a drawer sliding out of the main window leaves the columns and the scale as they are`() {
        val skin = Fitted(mmd3, pixel).apply { open("pl") }
        assertEquals(2, skin.rt.presentation.columns)
        assertEquals(2f, skin.layout().scale)
        val playlist = Rect(583, 0, 381, 870 / 2)
        assertEquals(
            "right of the main window's full width, as wide as its body",
            playlist,
            skin.rt.windowRect(skin.window("pl")),
        )

        skin.rt.beginSlideAsScript("drawer")
        skin.rt.setParamAsScript("drawer", "x", "383")
        assertEquals("while it slides", 2f, skin.layout().scale)
        skin.rt.endSlidesAsScript()
        assertEquals("once it rests", 2f, skin.layout().scale)
        assertEquals("the playlist keeps its width", playlist, skin.rt.windowRect(skin.window("pl")))

        // turned sideways or the playlist opened while the drawer is out: the same columns and scale
        skin.close("pl")
        skin.open("pl")
        assertEquals(2, skin.rt.presentation.columns)
        assertEquals(2f, skin.layout().scale)
        skin.rt.setParamAsScript("drawer", "x", "181")
        assertEquals("closed again", 2f, skin.layout().scale)
        assertEquals(playlist, skin.rt.windowRect(skin.window("pl")))
    }

    @Test
    fun `a tap on a row in the right column selects it, a second tap or a double tap plays it`() {
        val player = FakePlayer().apply { playlist = List(5) { Track("Track $it", 60_000) } }
        val modern = Fitted(winampModern, pixel, player).apply { open("pl") }
        assertEquals(2, modern.rt.presentation.columns)
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

        assertEquals(1, corner.rt.presentation.columns)
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
        val view = corner.rt.presentation.viewSize

        corner.fitting.apply(corner.rt, corner.fit, corner.baseW)
        corner.refit()
        assertEquals(first, corner.layout())
        assertEquals(view, corner.rt.presentation.viewSize)

        val mmd3 = Fitted(mmd3, pixel).apply { open("pl") }
        val scale = mmd3.layout().scale
        repeat(2) { mmd3.fitting.apply(mmd3.rt, mmd3.fit, mmd3.baseW) }
        mmd3.refit()
        assertEquals(scale, mmd3.layout().scale)
    }

    @Test
    fun `deciding the columns sends the skin no resize events`() {
        val corner = Fitted(cornerAmp, taller)
        check(corner.rt.openWindow("pl"))
        val passes = corner.rt.stats.layoutPasses

        corner.fitting.refit(corner.rt, corner.fit, corner.baseW)
        assertEquals(2, corner.rt.presentation.columns)
        assertEquals("no layout pass, so no window heard of a size it does not get", passes, corner.rt.stats.layoutPasses)
        corner.rt.ensureLayout()
        assertEquals("one pass sets the playlist where the decision put it", passes + 1, corner.rt.stats.layoutPasses)

        corner.fitting.refit(corner.rt, corner.fit, corner.baseW)
        corner.rt.ensureLayout()
        assertEquals("the same windows: nothing is decided again", passes + 1, corner.rt.stats.layoutPasses)
    }

    @Test
    fun `upright, a drawer that runs past the screen shrinks the skin once its slide ends, and grows back after`() {
        val upright = WalFit(720, 1066, 2f, sideways = false)
        val skin = Fitted(mmd3, upright)
        assertEquals(720f / 381, skin.layout().scale)

        skin.rt.beginSlideAsScript("drawer")
        skin.rt.setParamAsScript("drawer", "x", "383")
        assertEquals("while it slides the scale holds", 720f / 381, skin.layout().scale)
        skin.rt.endSlidesAsScript()
        assertEquals("once it rests the whole drawer fits", 720f / 583, skin.layout().scale)
        assertEquals(0f, skin.layout().left)

        skin.rt.beginSlideAsScript("drawer")
        skin.rt.setParamAsScript("drawer", "x", "181")
        assertEquals("closing, the scale holds", 720f / 583, skin.layout().scale)
        skin.rt.endSlidesAsScript()
        assertEquals("closed, it grows back", 720f / 381, skin.layout().scale)
    }

    @Test
    fun `upright, a window opened while the drawer is out still lets the skin grow back when it closes`() {
        val upright = WalFit(720, 1066, 2f, sideways = false)
        val skin = Fitted(mmd3, upright)
        skin.rt.setParamAsScript("drawer", "x", "383")
        assertEquals("the drawer out", 720f / 583, skin.layout().scale)

        skin.open("pl")
        assertEquals("the view the closed drawer gives", 381 to 564, skin.rt.presentation.viewSize)
        assertEquals(720f / 583, skin.layout().scale)

        skin.rt.setParamAsScript("drawer", "x", "181")
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

        skin.rt.switchLayoutAsScript("main", "shade")
        skin.refit()
        assertEquals("800 px across at 0.9x", 720f / 800, skin.layout().scale)
        assertEquals(800 to 1184, skin.rt.presentation.viewSize)
        assertEquals(Rect(0, 23, 800, 1184 - 23), skin.rt.windowRect(skin.window("pl")))

        skin.rt.switchLayoutAsScript("main", "normal")
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

    @Test
    fun `a main window moved into a corner of the viewport puts the stack in that corner of the box`() {
        // the Pixel upright and sideways, in the app and the floating player
        val fits = listOf(upright, upright.copy(compact = true), pixel, pixel.copy(compact = true))
        val corners = listOf(false to false, true to false, false to true, true to true)
        for ((fit, corner) in fits.flatMap { fit -> corners.map { fit to it } }) {
            val (right, down) = corner
            val layout = Fitted(cornerAmp, fit).apply { moveMain(right, down) }.layout()
            val name = "$fit, right $right, down $down"
            assertEquals(name, 3f, layout.scale)
            assertEquals(name, if (right) fit.widthPx - 246 * 3f else 0f, layout.left)
            assertEquals(name, if (down) fit.heightPx - 228 * 3f else 0f, layout.top)
        }
    }

    @Test
    fun `a main window not moved, or moved against no edge, leaves the stack centered`() {
        val unmoved = Fitted(cornerAmp, upright)
        assertEquals(111f, unmoved.layout().left)
        assertEquals(0f, unmoved.layout().top)

        val moved = Fitted(cornerAmp, upright).apply { moveMain(right = true, down = true) }
        moved.moveMain(10, 20)
        assertEquals(unmoved.layout(), moved.layout())

        val side = Fitted(cornerAmp, pixel)
        assertEquals((2142 - 738) / 2f, side.layout().left)
        assertEquals((870 - 684) / 2f, side.layout().top)
    }

    @Test
    fun `the stack stays in its corner when the phone turns or the floating player takes over`() {
        val corner = Fitted(cornerAmp, upright).apply { moveMain(right = true, down = true) }

        corner.refit(pixel)
        assertEquals(2142 - 738f, corner.layout().left)
        assertEquals(870 - 684f, corner.layout().top)
        val window = corner.window("main")
        val (vw, vh) = corner.rt.viewport
        assertEquals(
            "a script finds the window in the corner of the new viewport",
            vw - 246 to vh - 228,
            corner.rt.placeAsScript(window),
        )

        corner.refit(upright.copy(compact = true))
        assertEquals(960 - 738f, corner.layout().left)
        assertEquals(1900 - 684f, corner.layout().top)
    }

    @Test
    fun `a stack the listener moved keeps the move from its corner, and takes it along to a new corner`() {
        val box = IntRect(0, 100, 960, 2000)
        val corner =
            Fitted(
                cornerAmp,
                WalPlacement.fit(box, 2.25f, sideways = false),
            ).apply { moveMain(right = true, down = true) }
        val places = WalPlaces(upright = IntOffset(-60, -40))
        val move = checkNotNull(places[false])

        val home = WalPlacement.of(box, corner.layout(), 3).stack
        assertEquals("right down", IntRect(222, 1316, 960, 2000), home)
        val shift = WalPlacement.held(move, home, box, 144)
        assertEquals(
            "moved up and left from there",
            IntRect(162, 1276, 900, 1960),
            WalPlacement.of(box, corner.layout(), 3, shift).stack,
        )

        corner.moveMain(right = true, down = false)
        val up = WalPlacement.of(box, corner.layout(), 3).stack
        assertEquals("right up", IntRect(222, 100, 960, 784), up)
        assertEquals("the kept move stays as it was", IntOffset(-60, -40), places[false])
        assertEquals(
            "taken from the new corner, with its top kept in the box",
            IntOffset(-60, 0),
            WalPlacement.held(move, up, box, 144),
        )
    }

    @Test
    fun `a main window a script moves back to the place it read stays centered`() {
        // MMD3 reads where its main window is as it goes to its shade bar, puts the bar 16 px in at
        // the top of the screen, and moves the main window back to the place it read as it comes back
        for (fit in listOf(upright, upright.copy(compact = true), pixel, pixel.copy(compact = true))) {
            val skin = Fitted(mmd3, fit)
            val centered = skin.layout()
            val normal = skin.window("main")
            val (left, top) = skin.rt.placeAsScript(normal)

            skin.rt.switchLayoutAsScript("main", "shade")
            skin.moveMain(16, 0)
            skin.refit()
            assertEquals("$fit: the bar against the top", 0f, skin.layout().top)
            skin.rt.switchLayoutAsScript("main", "normal")
            skin.moveMain(left, top)
            skin.refit()
            assertEquals("$fit", centered, skin.layout())
        }
    }

    @Test
    fun `a main window the box sizes sits where the box puts it, wherever a script moved it, and so does its shade bar`() {
        for (fit in listOf(upright, upright.copy(compact = true), pixel, pixel.copy(compact = true))) {
            val unmoved = Fitted(bento, fit)
            // BigBento's script moves its main window to the top left corner, at the size the box gives it
            val moved = Fitted(bento, fit).apply { moveMain(0, 0) }
            assertEquals("$fit", unmoved.layout(), moved.layout())
            moved.moveMain(right = true, down = true)
            assertEquals("$fit", unmoved.layout(), moved.layout())

            for (skin in listOf(unmoved, moved)) {
                skin.rt.switchLayoutAsScript("main", "shade")
                skin.refit()
            }
            val shade = moved.layout()
            assertEquals("$fit: the shade bar", unmoved.layout(), shade)
            if (fit.sideways) {
                assertEquals(
                    "$fit: centered across",
                    kotlin.math.floor((fit.widthPx - shade.stackW * shade.scale) / 2),
                    shade.left,
                )
                assertEquals(
                    "$fit: and up and down",
                    kotlin.math.floor((fit.heightPx - shade.stackH * shade.scale) / 2),
                    shade.top,
                )
            }
        }
    }
}
