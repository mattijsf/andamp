// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.LockedColumns
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.window.LockedStack.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The column the windows are held in while the player fills the screen.
 *
 * The screen here is a phone's: exactly as wide as the player, 600 rows tall, with
 * everything from row 584 down kept by the system.
 */
class LockedStackTest {
    private val safeBottom = 584
    private val chromeH = 34

    @Suppress("LongParameterList") // one knob per thing the layout is asked about
    private fun ask(
        screenW: Int = MAIN_W,
        mainH: Int = MAIN_H,
        eqH: Int? = EQ_H,
        visChromeH: Int? = null,
        visSteps: Int? = null,
        playlist: Playlist = Playlist.OPEN,
        covers: List<String> = emptyList(),
        safeBottom: Int = this.safeBottom,
        columns: List<List<String>> = LockedColumns.defaultFor(sideways = false),
    ) = LockedStack.Ask(screenW, safeBottom, mainH, eqH, visChromeH, visSteps, playlist, covers, columns)

    @Test
    fun `the columns are laid out in the order they are given, the playlist filling wherever it stands`() {
        val rects = rects(ask(columns = listOf(listOf(WindowStore.PLAYLIST, WindowStore.MAIN, WindowStore.EQ))))

        // the player and the equalizer are 232 rows; the playlist takes the other 352 on top
        assertEquals(column(0, safeBottom - 232), rects[WindowStore.PLAYLIST])
        assertEquals(column(safeBottom - 232, safeBottom - 116), rects[WindowStore.MAIN])
        assertEquals(column(safeBottom - 116, safeBottom), rects[WindowStore.EQ])
    }

    @Test
    fun `the plug-in window under the playlist keeps its steps, and the playlist the rest`() {
        val places =
            LockedStack.layout(
                ask(
                    visChromeH = chromeH,
                    visSteps = 500,
                    columns = listOf(listOf(WindowStore.MAIN, WindowStore.EQ, WindowStore.PLAYLIST, WindowStore.MILKDROP)),
                ),
            )

        // as when it stands above the playlist: the rows the two fixed windows and the
        // playlist's least leave
        assertEquals((safeBottom - 232 - chromeH - 116) / MILKDROP_STEP, places.visMaxSteps)
        val visual = places.rects.getValue(WindowStore.MILKDROP)
        val playlist = places.rects.getValue(WindowStore.PLAYLIST)
        assertEquals(232, playlist.top)
        assertEquals(playlist.bottom, visual.top)
        assertEquals(safeBottom, visual.bottom)
    }

    @Test
    fun `the covers go under whatever is first in the column`() {
        val rects =
            rects(
                ask(
                    columns = listOf(listOf(WindowStore.EQ, WindowStore.MAIN, WindowStore.PLAYLIST)),
                    covers = listOf(WindowStore.LIBRARY),
                ),
            )

        assertEquals(setOf(WindowStore.EQ, WindowStore.LIBRARY), rects.keys)
        assertEquals(column(116, safeBottom), rects[WindowStore.LIBRARY])
    }

    @Test
    fun `sideways, the playlist moved to the left column takes what the two leave, and the right column is empty`() {
        val places =
            LockedStack.layout(
                sideways(
                    columns = listOf(listOf(WindowStore.MAIN, WindowStore.PLAYLIST, WindowStore.EQ), emptyList()),
                    safeBottom = 400,
                ),
            )

        assertEquals(IntRect(10, 116, 10 + MAIN_W, 400 - 116), places.rects[WindowStore.PLAYLIST])
        assertEquals(IntRect(10, 400 - 116, 10 + MAIN_W, 400), places.rects[WindowStore.EQ])
    }

    private fun rects(ask: LockedStack.Ask) = LockedStack.layout(ask).rects

    private fun column(
        from: Int,
        to: Int,
    ) = IntRect(0, from, MAIN_W, to)

    @Test
    fun `the player, the equalizer and the playlist are one column from the top to the safe bottom`() {
        val rects = rects(ask())

        assertEquals(column(0, 116), rects[WindowStore.MAIN])
        assertEquals(column(116, 232), rects[WindowStore.EQ])
        assertEquals(column(232, safeBottom), rects[WindowStore.PLAYLIST])
    }

    @Test
    fun `the playlist takes every row that is left, not a whole number of segments`() {
        val playlist = rects(ask()).getValue(WindowStore.PLAYLIST)

        assertFalse(
            "352 rows are not top, bottom and whole segments",
            playlist.height == PlaylistLayout.forAvailableHeight(playlist.height).height,
        )
        assertEquals(safeBottom, playlist.bottom)
    }

    @Test
    fun `a closed equalizer leaves its rows to the playlist`() {
        val rects = rects(ask(eqH = null))

        assertNull(rects[WindowStore.EQ])
        assertEquals(column(116, safeBottom), rects[WindowStore.PLAYLIST])
    }

    @Test
    fun `a collapsed window moves everything under it up`() {
        val rects = rects(ask(mainH = SHADE_H, eqH = SHADE_H))

        assertEquals(column(0, 14), rects[WindowStore.MAIN])
        assertEquals(column(14, 28), rects[WindowStore.EQ])
        assertEquals(column(28, safeBottom), rects[WindowStore.PLAYLIST])
    }

    @Test
    fun `a collapsed playlist is its bar under the stack, and a closed one is not there`() {
        assertEquals(column(232, 232 + SHADE_H), rects(ask(playlist = Playlist.SHADED))[WindowStore.PLAYLIST])
        assertNull(rects(ask(playlist = Playlist.CLOSED))[WindowStore.PLAYLIST])
    }

    @Test
    fun `the plug-in window sits between the equalizer and the playlist at its default height`() {
        val rects = rects(ask(visChromeH = chromeH))

        val visual = column(232, 232 + chromeH + MILKDROP_CONTENT_H)
        assertEquals(visual, rects[WindowStore.MILKDROP])
        assertEquals(column(visual.bottom, safeBottom), rects[WindowStore.PLAYLIST])
    }

    @Test
    fun `the plug-in window is as tall as it was asked to be`() {
        val rects = rects(ask(visChromeH = chromeH, visSteps = 20))

        assertEquals(chromeH + 20 * MILKDROP_STEP, rects.getValue(WindowStore.MILKDROP).height)
    }

    @Test
    fun `the plug-in window never takes the playlist's last two segments`() {
        val places = LockedStack.layout(ask(visChromeH = chromeH, visSteps = 500))

        // 584 - 232 rows are left; the frame and a 116-row playlist leave 202 for the visual
        assertEquals(202 / MILKDROP_STEP, places.visMaxSteps)
        assertEquals(chromeH + places.visMaxSteps * MILKDROP_STEP, places.rects.getValue(WindowStore.MILKDROP).height)
        val playlist = places.rects.getValue(WindowStore.PLAYLIST)
        assertEquals(safeBottom, playlist.bottom)
        assertEquals(116 + 202 % MILKDROP_STEP, playlist.height)
    }

    @Test
    fun `a collapsed or closed playlist leaves the plug-in window more room`() {
        val open = LockedStack.layout(ask(visChromeH = chromeH)).visMaxSteps
        val shaded = LockedStack.layout(ask(visChromeH = chromeH, playlist = Playlist.SHADED)).visMaxSteps
        val closed = LockedStack.layout(ask(visChromeH = chromeH, playlist = Playlist.CLOSED)).visMaxSteps

        assertEquals((584 - 232 - chromeH - SHADE_H) / MILKDROP_STEP, shaded)
        assertEquals((584 - 232 - chromeH) / MILKDROP_STEP, closed)
        assertEquals(true, open < shaded && shaded < closed)
    }

    @Test
    fun `on a screen too short for all of them the visual keeps its least and the playlist hangs off the bottom`() {
        val rects = rects(ask(visChromeH = chromeH, visSteps = 2, safeBottom = 406))

        assertEquals(chromeH + MILKDROP_MIN_STEPS * MILKDROP_STEP, rects.getValue(WindowStore.MILKDROP).height)
        assertEquals(116, rects.getValue(WindowStore.PLAYLIST).height)
    }

    @Test
    fun `an open library covers everything under the player`() {
        val rects = rects(ask(visChromeH = chromeH, covers = listOf(WindowStore.LIBRARY)))

        assertEquals(setOf(WindowStore.MAIN, WindowStore.LIBRARY), rects.keys)
        assertEquals(column(116, safeBottom), rects[WindowStore.LIBRARY])
    }

    @Test
    fun `the library and the skin browser cover the same rows`() {
        val rects = rects(ask(mainH = SHADE_H, covers = listOf(WindowStore.LIBRARY, WindowStore.SKINS)))

        assertEquals(column(14, safeBottom), rects[WindowStore.LIBRARY])
        assertEquals(rects[WindowStore.LIBRARY], rects[WindowStore.SKINS])
    }

    /** Sideways: a screen two players wide, [safeBottom] rows tall above the system's strip. */
    private fun sideways(
        visChromeH: Int? = null,
        visSteps: Int? = null,
        playlist: Playlist = Playlist.OPEN,
        covers: List<String> = emptyList(),
        safeBottom: Int = 232,
        columns: List<List<String>> = LockedColumns.defaultFor(sideways = true),
    ) = ask(
        screenW = 2 * MAIN_W + 20,
        safeBottom = safeBottom,
        visChromeH = visChromeH,
        visSteps = visSteps,
        playlist = playlist,
        covers = covers,
        columns = columns,
    )

    @Test
    fun `sideways, the player and the equalizer are the left column and the playlist the right`() {
        val rects = rects(sideways())

        assertEquals(IntRect(10, 0, 10 + MAIN_W, 116), rects[WindowStore.MAIN])
        assertEquals(IntRect(10, 116, 10 + MAIN_W, 232), rects[WindowStore.EQ])
        assertEquals(IntRect(10 + MAIN_W, 0, 10 + 2 * MAIN_W, 232), rects[WindowStore.PLAYLIST])
    }

    @Test
    fun `sideways, the plug-in window has the rest of the left column, the playlist being beside it`() {
        val places = LockedStack.layout(sideways(visChromeH = chromeH, visSteps = 500, safeBottom = 400))

        // 400 - 232 rows are left under the equalizer, with no playlist to leave room for
        assertEquals((400 - 232 - chromeH) / MILKDROP_STEP, places.visMaxSteps)
        val visual = places.rects.getValue(WindowStore.MILKDROP)
        assertEquals(IntRect(10, 232, 10 + MAIN_W, 232 + chromeH + places.visMaxSteps * MILKDROP_STEP), visual)
        assertEquals(400, places.rects.getValue(WindowStore.PLAYLIST).bottom)
    }

    @Test
    fun `sideways, a collapsed playlist is its bar at the top of the right column`() {
        val rects = rects(sideways(playlist = Playlist.SHADED))

        assertEquals(IntRect(10 + MAIN_W, 0, 10 + 2 * MAIN_W, SHADE_H), rects[WindowStore.PLAYLIST])
        assertNull(rects(sideways(playlist = Playlist.CLOSED))[WindowStore.PLAYLIST])
    }

    @Test
    fun `sideways, an open library takes the right column and the stack stays`() {
        val rects = rects(sideways(covers = listOf(WindowStore.LIBRARY)))

        assertEquals(setOf(WindowStore.MAIN, WindowStore.EQ, WindowStore.LIBRARY), rects.keys)
        assertEquals(IntRect(10 + MAIN_W, 0, 10 + 2 * MAIN_W, 232), rects[WindowStore.LIBRARY])
    }

    @Test
    fun `a drag finds its column by where it is across the surface`() {
        // two columns in the middle of a 601 wide surface: 25..300 and 300..575
        assertEquals(0, LockedStack.columnAt(100, screenW = 601, across = 2))
        assertEquals(1, LockedStack.columnAt(400, screenW = 601, across = 2))
        assertEquals("outside the columns, the nearest", 1, LockedStack.columnAt(600, screenW = 601, across = 2))
        assertEquals(0, LockedStack.columnAt(-50, screenW = 601, across = 2))
        assertEquals(0, LockedStack.columnAt(900, screenW = 601, across = 1))
    }

    @Test
    fun `on a screen wider than the player the column is in the middle`() {
        val rects = rects(ask(screenW = 601))

        assertEquals(IntRect(163, 0, 163 + MAIN_W, 116), rects[WindowStore.MAIN])
        assertEquals(163, rects.getValue(WindowStore.PLAYLIST).left)
    }
}
