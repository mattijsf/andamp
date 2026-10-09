// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.LockedColumns
import nl.mattix.andamp.state.WindowStore

/**
 * Where every window sits while the player fills the screen, with nothing free to float.
 *
 * The windows stand in columns as wide as the player, one upright and two sideways, in the
 * order the workspace keeps ([LockedColumns]): the player over the equalizer, the plug-in
 * window and the playlist to begin with, and sideways the playlist in the right column. A
 * window takes the row the one above ends on, so closing or collapsing one moves the rest
 * up, and the playlist takes every row the others of its column leave, wherever it stands.
 * A listener reorders them by dragging; see [nl.mattix.andamp.state.WinampState.liftWindow].
 *
 * The library and the skin browser are not in the columns: upright an open one covers
 * everything under the first window, sideways it takes the right column.
 */
object LockedStack {
    /** The form the playlist is in. */
    enum class Playlist { CLOSED, SHADED, OPEN }

    /** What is on screen, and the screen it is on, in virtual pixels. */
    data class Ask(
        val screenW: Int,
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
        /** Whether the phone is held sideways; see [LockedStack]. */
        val sideways: Boolean = false,
        /** The windows of each column, top to bottom. */
        val columns: List<List<String>> = LockedColumns.defaultFor(sideways),
    )

    /** Each window's rectangle by its id; a window that is not listed is not shown. */
    data class Places(
        val rects: Map<String, IntRect>,
        /** The most steps the plug-in window's visual may take and still leave the playlist its room. */
        val visMaxSteps: Int = MILKDROP_MIN_STEPS,
        /** Each column's band of the surface, left to right, for a drag to find its column. */
        val bands: List<IntRect> = emptyList(),
    )

    fun layout(ask: Ask): Places {
        val across = ask.columns.size
        val first = (ask.screenW - across * MAIN_W) / 2
        val bands = ask.columns.indices.map { i -> IntRect(first + i * MAIN_W, 0, first + (i + 1) * MAIN_W, ask.safeBottom) }
        val rects = LinkedHashMap<String, IntRect>()
        var visMaxSteps = MILKDROP_MIN_STEPS
        ask.columns.forEachIndexed { i, column ->
            val shown = column.filter { ask.shows(it) }
            val heights = ask.heightsOf(shown)
            heights.visMaxSteps?.let { visMaxSteps = it }
            // nothing in the stack is dragged, so each column starts on the surface's first row
            var row = 0
            shown.forEach { id ->
                val height = heights.of(id)
                rects[id] = IntRect(bands[i].left, row, bands[i].right, row + height)
                row += height
            }
        }
        if (ask.covers.isNotEmpty()) cover(ask, rects, bands)
        return Places(rects, visMaxSteps, bands)
    }

    /** How tall each window of one column is; see [heightsOf]. */
    private class Heights(
        private val mainH: Int,
        private val eqH: Int,
        private val visH: Int,
        private val playlistH: Int,
        /** The most steps the visual may take in this column; null while it is not in it. */
        val visMaxSteps: Int?,
    ) {
        fun of(id: String) =
            when (id) {
                WindowStore.MAIN -> mainH
                WindowStore.EQ -> eqH
                WindowStore.MILKDROP -> visH
                else -> playlistH
            }
    }

    /**
     * The heights of the windows [shown] in one column. The player and the equalizer are as
     * tall as they are; the visual takes the steps it asks for out of what they leave, less
     * the playlist's least when it shares the column; and the playlist takes every row the
     * others leave, wherever it stands.
     */
    private fun Ask.heightsOf(shown: List<String>): Heights {
        val eqH = eqH ?: 0
        val fixed = (if (WindowStore.MAIN in shown) mainH else 0) + (if (WindowStore.EQ in shown) eqH else 0)
        val playlistLeast =
            when (playlist) {
                Playlist.OPEN -> PlaylistLayout.ofSegments(PlaylistLayout.MIN_SEGMENTS).height
                Playlist.SHADED -> SHADE_H
                Playlist.CLOSED -> 0
            }
        var visMaxSteps: Int? = null
        var visH = 0
        if (WindowStore.MILKDROP in shown) {
            val chromeH = visChromeH ?: 0
            val least = if (WindowStore.PLAYLIST in shown) playlistLeast else 0
            val most = ((safeBottom - fixed - chromeH - least) / MILKDROP_STEP).coerceAtLeast(MILKDROP_MIN_STEPS)
            visMaxSteps = most
            visH = chromeH + (visSteps ?: MILKDROP_DEFAULT_STEPS).coerceIn(MILKDROP_MIN_STEPS, most) * MILKDROP_STEP
        }
        val playlistH =
            when (playlist) {
                Playlist.SHADED -> SHADE_H
                Playlist.OPEN -> maxOf(safeBottom - fixed - visH, playlistLeast)
                Playlist.CLOSED -> 0
            }
        return Heights(mainH, eqH, visH, playlistH, visMaxSteps)
    }

    /** Whether [id] is on screen: the player always, the others while open. */
    private fun Ask.shows(id: String) =
        when (id) {
            WindowStore.MAIN -> true
            WindowStore.EQ -> eqH != null
            WindowStore.MILKDROP -> visChromeH != null
            WindowStore.PLAYLIST -> playlist != Playlist.CLOSED
            else -> false
        }

    /**
     * Puts the covers in: upright over everything under the first window of the column,
     * sideways over the whole right column. What they cover is not shown.
     */
    private fun cover(
        ask: Ask,
        rects: LinkedHashMap<String, IntRect>,
        bands: List<IntRect>,
    ) {
        val covered: IntRect
        if (ask.sideways) {
            ask.columns[1].forEach { rects.remove(it) }
            covered = bands[1]
        } else {
            val top = ask.columns[0].firstOrNull { it in rects } ?: return
            ask.columns[0].drop(1).forEach { rects.remove(it) }
            val under = rects.getValue(top).bottom
            covered = IntRect(bands[0].left, under, bands[0].right, maxOf(ask.safeBottom, under))
        }
        ask.covers.forEach { rects[it] = covered }
    }
}
