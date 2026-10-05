// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WindowStore

/**
 * Where every window sits while the player fills the screen: one column as wide as the
 * player, with nothing free to move.
 *
 * The player is on top, with the equalizer, the plug-in window and the playlist under it in
 * that order. Each takes the row the one above ends on, so closing or collapsing a window
 * moves the rest up, and the playlist takes every row that is left. The library and the
 * skin browser are not part of the column: an open one covers everything under the player.
 */
object LockedStack {
    /** The form the playlist is in. */
    enum class Playlist { CLOSED, SHADED, OPEN }

    /** What is on screen, and the screen it is on, in virtual pixels. */
    data class Ask(
        val screenW: Int,
        /** The first row a window may take. */
        val top: Int,
        /** The row the system's own strip at the bottom begins on. */
        val safeBottom: Int,
        /** The player's height, collapsed or not. */
        val mainH: Int,
        /** The equalizer's height, collapsed or not; null while it is closed. */
        val eqH: Int? = null,
        /** The height of the plug-in window's frame around its visual; null while it is closed. */
        val visChromeH: Int? = null,
        /** How many steps tall the visual is asked to be; null is its default. */
        val visSteps: Int? = null,
        val playlist: Playlist = Playlist.CLOSED,
        /** The open windows that cover the column under the player, by their ids. */
        val covers: List<String> = emptyList(),
    )

    /** Each window's rectangle by its id; a window that is not listed is not shown. */
    data class Places(
        val rects: Map<String, IntRect>,
        /** The most steps the plug-in window's visual may take and still leave the playlist its room. */
        val visMaxSteps: Int = MILKDROP_MIN_STEPS,
    )

    fun layout(ask: Ask): Places {
        val left = (ask.screenW - MAIN_W) / 2
        val rects = LinkedHashMap<String, IntRect>()
        var row = ask.top

        fun take(
            id: String,
            height: Int,
        ) {
            rects[id] = IntRect(left, row, left + MAIN_W, row + height)
            row += height
        }

        take(WindowStore.MAIN, ask.mainH)
        if (ask.covers.isNotEmpty()) {
            val cover = IntRect(left, row, left + MAIN_W, maxOf(ask.safeBottom, row))
            ask.covers.forEach { rects[it] = cover }
            return Places(rects)
        }
        ask.eqH?.let { take(WindowStore.EQ, it) }

        // the playlist keeps the least it can be drawn at, and the visual gets what is left
        val playlistLeast =
            when (ask.playlist) {
                Playlist.OPEN -> PlaylistLayout.ofSegments(PlaylistLayout.MIN_SEGMENTS).height
                Playlist.SHADED -> SHADE_H
                Playlist.CLOSED -> 0
            }
        var visMaxSteps = MILKDROP_MIN_STEPS
        ask.visChromeH?.let { chromeH ->
            val room = ask.safeBottom - row - playlistLeast - chromeH
            visMaxSteps = (room / MILKDROP_STEP).coerceAtLeast(MILKDROP_MIN_STEPS)
            val steps = (ask.visSteps ?: MILKDROP_DEFAULT_STEPS).coerceIn(MILKDROP_MIN_STEPS, visMaxSteps)
            take(WindowStore.MILKDROP, chromeH + steps * MILKDROP_STEP)
        }
        when (ask.playlist) {
            Playlist.OPEN -> take(WindowStore.PLAYLIST, maxOf(ask.safeBottom - row, playlistLeast))
            Playlist.SHADED -> take(WindowStore.PLAYLIST, SHADE_H)
            Playlist.CLOSED -> Unit
        }
        return Places(rects, visMaxSteps)
    }
}
