// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.graphics.drawscope.DrawScope
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.Sprite
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.sprite

const val PL_W = 275

/** Playlist geometry for a given quantized window height. */
class PlaylistLayout(
    val height: Int,
    /** The window's width: 275 at its narrowest, growing in steps of [WIDTH_STEP]. */
    val width: Int = PL_W,
    /** How tall a row of the list is: Winamp's 13 px, or what the listener picked. */
    val rowH: Int = Dest.PL_ROW_H,
) {
    /** The width index: width = 275 + 25 * cols. */
    val cols = (width - PL_W) / WIDTH_STEP

    /**
     * Where the bottom bar's right-hand cap begins. The readouts, the mini transport and
     * LIST OPTS are anchored to it, and extra width opens as a gap in the middle of the bar
     * (webamp: .playlist-bottom-right is `right: 0; width: 150`).
     */
    val rightCapX = width - PL_RIGHT_CAP_W

    /** The scrollbar's rail, pinned to the right edge. */
    val scrollX = width - Dest.PL_RIGHT_W + 5
    val middleH = height - Dest.PL_TOP_H - Dest.PL_BOTTOM_H
    val textTop = Dest.PL_TOP_H + 3
    val visibleRows = ((middleH - 6) / rowH).coerceAtLeast(1)
    val textLeft = Dest.PL_LEFT_W
    val textRight = width - Dest.PL_RIGHT_W
    val scrollTrackTop = Dest.PL_TOP_H
    val scrollTrackH = middleH - 18

    /** How many 29px middle tiles this window is made of. */
    val segments = (height - Dest.PL_TOP_H - Dest.PL_BOTTOM_H) / Dest.PL_TILE_STEP

    /**
     * The first row to show so that [row] of [rows] is in view, coming from [scroll]: where
     * the list is while the row already shows, and otherwise the place that puts the row in
     * the middle, with rows before and after it.
     */
    fun scrollShowing(
        row: Int,
        scroll: Int,
        rows: Int,
    ): Int {
        if (row !in 0 until rows || row in scroll until scroll + visibleRows) return scroll
        return (row - visibleRows / 2).coerceIn(0, (rows - visibleRows).coerceAtLeast(0))
    }

    companion object {
        /** Winamp's minimum: two tiles, a 116px window. */
        const val MIN_SEGMENTS = 2

        /** webamp's WINDOW_RESIZE_SEGMENT_WIDTH: one step sideways. */
        const val WIDTH_STEP = WidthSteps.WIDTH_STEP

        /** How the playlist widens. */
        val WIDTH_STEPS = WidthSteps(PL_W, WIDTH_STEP)

        /** The width of the bottom bar's right-hand cap. */
        const val PL_RIGHT_CAP_W = 150

        fun widthOfCols(cols: Int) = WIDTH_STEPS.widthOf(cols)

        /** The height of the top and bottom bars together. */
        const val FURNITURE_H = Dest.PL_TOP_H + Dest.PL_BOTTOM_H

        /** The tallest layout of whole 29px segments that fits [availableVirtual]. */
        fun forAvailableHeight(availableVirtual: Int): PlaylistLayout = ofSegments(segmentsThatFit(availableVirtual))

        fun ofSegments(
            segments: Int,
            width: Int = PL_W,
            rowH: Int = Dest.PL_ROW_H,
        ) = PlaylistLayout(
            Dest.PL_TOP_H + segments.coerceAtLeast(MIN_SEGMENTS) * Dest.PL_TILE_STEP + Dest.PL_BOTTOM_H,
            width,
            rowH,
        )

        fun segmentsThatFit(availableVirtual: Int): Int =
            ((availableVirtual - Dest.PL_TOP_H - Dest.PL_BOTTOM_H) / Dest.PL_TILE_STEP).coerceAtLeast(MIN_SEGMENTS)
    }
}

/** Fills [x0]..[x1] with [tile], the odd remainder first so whole tiles land where Winamp's did. */
private fun DrawScope.fillTiles(
    sheet: androidx.compose.ui.graphics.ImageBitmap,
    tile: Sprite,
    x0: Int,
    x1: Int,
) {
    val gap = x1 - x0
    if (gap <= 0) return
    val remainder = gap % tile.w
    if (remainder > 0) sprite(sheet, Sprite(tile.x, tile.y, remainder, tile.h), x0, 0)
    var x = x0 + remainder
    while (x < x1) {
        sprite(sheet, tile, x, 0)
        x += tile.w
    }
}

/**
 * The playlist's top bar across [width], always in the focused variants: a corner at each
 * end, the title plate centered, and the gaps filled with tiles, the odd remainder first.
 * The close and shade buttons show their pressed art.
 */
internal fun DrawScope.drawPlaylistTop(
    skin: Skin,
    s: WinampState,
    width: Int,
) {
    val sheet = skin[Sheet.PLEDIT]
    sprite(sheet, SpriteMap.PLAYLIST_TOP_LEFT_SELECTED, 0, 0)
    val tile = SpriteMap.PLAYLIST_TOP_TILE_SELECTED
    val titleW = SpriteMap.PLAYLIST_TITLE_BAR_SELECTED.w
    // webamp's row: corner, a 12px spacer, fill, the title plate, a 13px spacer, fill,
    // corner, with the two spacers only when the width index is even
    // (PlaylistWindow/index.tsx: showSpacers = size[0] % 2 === 0)
    val spacers = (width - PL_W) / PlaylistLayout.WIDTH_STEP % 2 == 0
    val leftSpacer = if (spacers) 12 else 0
    val rightSpacer = if (spacers) 13 else 0
    val slack = width - 50 - titleW - leftSpacer - rightSpacer
    val leftFill = slack / 2
    if (leftSpacer > 0) sprite(sheet, Sprite(tile.x, tile.y, leftSpacer, tile.h), 25, 0)
    fillTiles(sheet, tile, 25 + leftSpacer, 25 + leftSpacer + leftFill)
    val titleX = 25 + leftSpacer + leftFill
    sprite(sheet, SpriteMap.PLAYLIST_TITLE_BAR_SELECTED, titleX, 0)
    if (rightSpacer > 0) sprite(sheet, Sprite(tile.x, tile.y, rightSpacer, tile.h), titleX + titleW, 0)
    fillTiles(sheet, tile, titleX + titleW + rightSpacer, width - 25)
    sprite(sheet, SpriteMap.PLAYLIST_TOP_RIGHT_CORNER_SELECTED, width - 25, 0)

    if (s.pressedWidget == "pl.close") sprite(sheet, SpriteMap.PLAYLIST_CLOSE_SELECTED, width - 11, Dest.PL_CLOSE.y)
    if (s.pressedWidget == "pl.shade") sprite(sheet, SpriteMap.PLAYLIST_COLLAPSE_SELECTED, width - 21, Dest.PL_SHADE.y)
}

/**
 * The playlist's side tiles at the two edges of [width], from [top] down to [bottom]. The
 * last tile is cut where the rectangle ends.
 */
internal fun DrawScope.drawPlaylistSides(
    skin: Skin,
    width: Int,
    top: Int,
    bottom: Int,
) {
    val sheet = skin[Sheet.PLEDIT]
    var ty = top
    while (ty < bottom) {
        val tileH = minOf(Dest.PL_TILE_STEP, bottom - ty)
        val left = SpriteMap.PLAYLIST_LEFT_TILE
        val right = SpriteMap.PLAYLIST_RIGHT_TILE
        sprite(sheet, Sprite(left.x, left.y, left.w, tileH), 0, ty)
        sprite(sheet, Sprite(right.x, right.y, right.w, tileH), width - Dest.PL_RIGHT_W, ty)
        ty += tileH
    }
}
