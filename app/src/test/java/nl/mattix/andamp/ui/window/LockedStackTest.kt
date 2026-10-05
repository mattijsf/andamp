// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.window.LockedStack.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The column the windows are held in while the player fills the screen.
 *
 * The screen here is a phone's: exactly as wide as the player, 600 rows tall, with the top
 * four rows and everything from row 588 down kept by the system.
 */
class LockedStackTest {
    private val top = 4
    private val safeBottom = 588
    private val chromeH = 34

    private fun ask(
        screenW: Int = MAIN_W,
        mainH: Int = MAIN_H,
        eqH: Int? = EQ_H,
        visChromeH: Int? = null,
        visSteps: Int? = null,
        playlist: Playlist = Playlist.OPEN,
        covers: List<String> = emptyList(),
        safeBottom: Int = this.safeBottom,
    ) = LockedStack.Ask(screenW, top, safeBottom, mainH, eqH, visChromeH, visSteps, playlist, covers)

    private fun rects(ask: LockedStack.Ask) = LockedStack.layout(ask).rects

    private fun column(
        from: Int,
        to: Int,
    ) = IntRect(0, from, MAIN_W, to)

    @Test
    fun `the player, the equalizer and the playlist are one column from the top to the safe bottom`() {
        val rects = rects(ask())

        assertEquals(column(4, 120), rects[WindowStore.MAIN])
        assertEquals(column(120, 236), rects[WindowStore.EQ])
        assertEquals(column(236, safeBottom), rects[WindowStore.PLAYLIST])
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
        assertEquals(column(120, safeBottom), rects[WindowStore.PLAYLIST])
    }

    @Test
    fun `a collapsed window moves everything under it up`() {
        val rects = rects(ask(mainH = SHADE_H, eqH = SHADE_H))

        assertEquals(column(4, 18), rects[WindowStore.MAIN])
        assertEquals(column(18, 32), rects[WindowStore.EQ])
        assertEquals(column(32, safeBottom), rects[WindowStore.PLAYLIST])
    }

    @Test
    fun `a collapsed playlist is its bar under the stack, and a closed one is not there`() {
        assertEquals(column(236, 236 + SHADE_H), rects(ask(playlist = Playlist.SHADED))[WindowStore.PLAYLIST])
        assertNull(rects(ask(playlist = Playlist.CLOSED))[WindowStore.PLAYLIST])
    }

    @Test
    fun `the plug-in window sits between the equalizer and the playlist at its default height`() {
        val rects = rects(ask(visChromeH = chromeH))

        val visual = column(236, 236 + chromeH + MILKDROP_CONTENT_H)
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

        // 588 - 236 rows are left; the frame and a 116-row playlist leave 202 for the visual
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

        assertEquals((588 - 236 - chromeH - SHADE_H) / MILKDROP_STEP, shaded)
        assertEquals((588 - 236 - chromeH) / MILKDROP_STEP, closed)
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
        assertEquals(column(120, safeBottom), rects[WindowStore.LIBRARY])
    }

    @Test
    fun `the library and the skin browser cover the same rows`() {
        val rects = rects(ask(mainH = SHADE_H, covers = listOf(WindowStore.LIBRARY, WindowStore.SKINS)))

        assertEquals(column(18, safeBottom), rects[WindowStore.LIBRARY])
        assertEquals(rects[WindowStore.LIBRARY], rects[WindowStore.SKINS])
    }

    @Test
    fun `on a screen wider than the player the column is in the middle`() {
        val rects = rects(ask(screenW = 601))

        assertEquals(IntRect(163, 4, 163 + MAIN_W, 120), rects[WindowStore.MAIN])
        assertEquals(163, rects.getValue(WindowStore.PLAYLIST).left)
    }
}
