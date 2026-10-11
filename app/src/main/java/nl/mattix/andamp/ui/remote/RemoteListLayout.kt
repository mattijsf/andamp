// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.ui.window.ListText
import nl.mattix.andamp.ui.window.PlaylistLayout

/**
 * Where the playlist on a lent surface puts its parts, in the window's own pixels.
 *
 * It is the skin's playlist frame without the bottom bar: the top bar, the two sides, and
 * between them rows made for a finger. A row is at least twice the height of the playlist
 * window's, and at least [touchPx] surface pixels, the smallest thing a car's screen asks a
 * driver to hit. The window is as wide as whole steps of the frame allow in a room [roomW] by
 * [roomH] surface pixels at [scale], and as tall as the room.
 */
internal class RemoteListLayout(
    roomW: Int,
    roomH: Int,
    scale: Int,
    touchPx: Int = 0,
) {
    /** The height of a row. */
    val rowH = maxOf(Dest.PL_ROW_H * 2, (touchPx + scale - 1) / scale)

    /** The size of the rows' text, which keeps the share of a row it has in the playlist window. */
    val fontSize = rowH * ListText.FONT_SIZE / Dest.PL_ROW_H * TEXT_SHARE

    val width = PlaylistLayout.widthOfCols(PlaylistLayout.WIDTH_STEPS.colsThatFit(roomW / scale))
    val height = (roomH / scale).coerceAtLeast(Dest.PL_TOP_H + rowH)

    val listLeft = Dest.PL_LEFT_W
    val listRight = width - Dest.PL_RIGHT_W
    val listTop = Dest.PL_TOP_H
    val listH = height - listTop

    /** The right end of the top bar: the close button's reach, which is wider than its X. */
    val closeReach = IntRect(width - CLOSE_REACH_W, 0, width, listTop)

    /** How far a list of [rows] rows scrolls. */
    fun farthest(rows: Int): Int = (rows * rowH - listH).coerceAtLeast(0)

    /** The row under [y] with the list scrolled down by [offset]; it may be past the last one. */
    fun rowAt(
        y: Float,
        offset: Float,
    ): Int = ((y - listTop + offset) / rowH).toInt()

    /** How far to scroll a list of [rows] rows to have [row] in the middle. */
    fun showing(
        row: Int,
        rows: Int,
    ): Int = (row * rowH - (listH - rowH) / 2).coerceIn(0, farthest(rows))

    companion object {
        const val CLOSE_REACH_W = 40

        /** How much of the playlist window's text-to-row share the text keeps: a little air around it. */
        const val TEXT_SHARE = 0.75f

        /** The smallest touch target on a car's screen, in dp, from Android Auto's design rules. */
        const val TOUCH_DP = 76
    }
}
