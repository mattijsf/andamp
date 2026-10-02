// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.Sprite
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.bitmapText
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import kotlin.math.roundToInt

const val PL_W = 275

/** Playlist geometry for a given quantized window height. */
class PlaylistLayout(
    val height: Int,
    /** The window's width: 275 at its narrowest, growing in steps of [WIDTH_STEP]. */
    val width: Int = PL_W,
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
    val visibleRows = ((middleH - 6) / Dest.PL_ROW_H).coerceAtLeast(1)
    val textLeft = Dest.PL_LEFT_W
    val textRight = width - Dest.PL_RIGHT_W
    val scrollTrackTop = Dest.PL_TOP_H
    val scrollTrackH = middleH - 18

    /** How many 29px middle tiles this window is made of. */
    val segments = (height - Dest.PL_TOP_H - Dest.PL_BOTTOM_H) / Dest.PL_TILE_STEP

    companion object {
        /** Winamp's minimum: two tiles, a 116px window. */
        const val MIN_SEGMENTS = 2

        /** webamp's WINDOW_RESIZE_SEGMENT_WIDTH: one step sideways. */
        const val WIDTH_STEP = 25

        /** The width of the bottom bar's right-hand cap. */
        const val PL_RIGHT_CAP_W = 150

        fun widthOfCols(cols: Int) = PL_W + cols.coerceAtLeast(0) * WIDTH_STEP

        /** The height of the top and bottom bars together. */
        const val FURNITURE_H = Dest.PL_TOP_H + Dest.PL_BOTTOM_H

        /** The tallest layout of whole 29px segments that fits [availableVirtual]. */
        fun forAvailableHeight(availableVirtual: Int): PlaylistLayout = ofSegments(segmentsThatFit(availableVirtual))

        fun ofSegments(
            segments: Int,
            width: Int = PL_W,
        ) = PlaylistLayout(
            Dest.PL_TOP_H + segments.coerceAtLeast(MIN_SEGMENTS) * Dest.PL_TILE_STEP + Dest.PL_BOTTOM_H,
            width,
        )

        fun segmentsThatFit(availableVirtual: Int): Int =
            ((availableVirtual - Dest.PL_TOP_H - Dest.PL_BOTTOM_H) / Dest.PL_TILE_STEP).coerceAtLeast(MIN_SEGMENTS)
    }
}

@Suppress("LongParameterList") // a window's rows, its menus and its own placement
fun playlistWidgets(
    vm: WindowControls,
    layout: PlaylistLayout,
    actions: PlaylistMenuActions = PlaylistMenuActions(),
    /** Runs a tapped menu entry. */
    onMenuEntry: (PlaylistMenuEntry) -> Unit = {},
    /** A widget to place under all the others, or null. */
    chrome: Widget? = null,
    /** The menu a long press on a row opens, or null for none. */
    rowMenu: (Int) -> AmpMenu? = { null },
): List<Widget> {
    val s = vm.state
    val widgets = mutableListOf<Widget>()
    // first, so every control on top of it wins the hit test
    if (chrome != null) widgets += chrome

    widgets += playlistRows(vm, s, layout, rowMenu)

    // scrollbar: drag the 8x18 handle inside the right edge
    widgets +=
        Widget(
            "pl.scroll",
            androidx.compose.ui.unit.IntRect(
                layout.width - Dest.PL_RIGHT_W,
                layout.scrollTrackTop,
                layout.width,
                layout.scrollTrackTop + layout.middleH,
            ),
            pointer =
                Widget.Pointer(
                    onDown = { pos -> scrollFromPointer(s, layout, pos.y) },
                    onDrag = { pos -> scrollFromPointer(s, layout, pos.y) },
                ),
        )
    // scroll arrows; their art is part of the right tile
    widgets +=
        button("pl.scrollUp", layout.scrollX, layout.scrollTrackTop + 2, 8, 5) { vm.scrollBy(-1, layout.visibleRows) }
    widgets +=
        button(
            "pl.scrollDown",
            layout.scrollX,
            layout.scrollTrackTop + 8,
            8,
            5,
        ) { vm.scrollBy(1, layout.visibleRows) }

    // mini transport in the bottom-right corner; its art is part of PLEDIT.BMP
    val btnY = layout.height - Dest.PL_BOTTOM_H + 22
    val btnX = layout.rightCapX + 3
    widgets += button("pl.prev", btnX, btnY, 10, 10, onTap = vm::previous)
    widgets += button("pl.play", btnX + 10, btnY, 10, 10, onTap = vm::play)
    widgets += button("pl.pause", btnX + 20, btnY, 10, 10, onTap = vm::pause)
    widgets += button("pl.stop", btnX + 30, btnY, 10, 10, onTap = vm::stop)
    widgets += button("pl.next", btnX + 40, btnY, 10, 10, onTap = vm::next)

    // the two title-bar buttons, in the right-hand corner piece
    widgets += button("pl.close", layout.width - 11, Dest.PL_CLOSE.y, 9, 9) { s.plVisible = false }
    widgets +=
        button("pl.shade", layout.width - 21, Dest.PL_SHADE.y, 9, 9, inert = { !s.shadeEnabled }) {
            s.setShaded(WindowStore.PLAYLIST, true, layout.height)
        }

    // expanding bottom-bar menus. Order matters for hit-testing (last enabled
    // widget wins): buttons, then the close-on-outside-tap scrim, then entries.
    val menus = playlistMenus(actions, layout.width)
    val buttonY = layout.height - Dest.PL_BOTTOM_H + 8 // css bottom:12 in the 38px bar
    for (menu in menus) {
        widgets +=
            button(
                menu.id,
                menu.buttonX,
                buttonY,
                PlaylistMenu.ENTRY_W,
                PlaylistMenu.ENTRY_H,
            ) {
                s.openMenu = if (s.openMenu == menu.id) null else menu.id
            }
    }
    widgets +=
        Widget(
            "pl.menuScrim",
            androidx.compose.ui.unit
                .IntRect(0, 0, layout.width, layout.height),
            enabled = { s.openMenu != null },
            pointer = Widget.Pointer(onDown = { s.openMenu = null }),
        )
    for (menu in menus) {
        menu.entries.forEachIndexed { i, entry ->
            val y = menu.topY(layout.height) + i * PlaylistMenu.ENTRY_H
            widgets +=
                Widget(
                    entry.id,
                    androidx.compose.ui.unit.IntRect(
                        menu.buttonX,
                        y,
                        menu.buttonX + PlaylistMenu.ENTRY_W,
                        y + PlaylistMenu.ENTRY_H,
                    ),
                    enabled = { s.openMenu == menu.id },
                    taps =
                        Widget.Taps(
                            onTap = {
                                s.openMenu = null
                                onMenuEntry(entry)
                            },
                        ),
                    // the entries are identical stacked tiles, so a hold opens the lens
                    magnify = true,
                )
        }
    }

    return widgets
}

private fun scrollFromPointer(
    s: WinampState,
    layout: PlaylistLayout,
    y: Float,
) {
    val maxScroll = (s.playlist.size - layout.visibleRows).coerceAtLeast(0)
    if (maxScroll == 0) return
    val frac = ((y - layout.scrollTrackTop - 9) / layout.scrollTrackH).coerceIn(0f, 1f)
    s.playlistScroll = (frac * maxScroll).roundToInt()
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

fun DrawScope.drawPlaylistWindow(
    skin: Skin,
    s: WinampState,
    layout: PlaylistLayout,
    textLayer: PlaylistTextRasterizer,
    scale: Int = 1,
) {
    val sheet = skin[Sheet.PLEDIT]
    val style = skin.pledit
    val h = layout.height

    // --- top bar, always in the focused variants: a corner at each end, the title plate
    // centered, and the gaps filled with tiles, the odd remainder first ---
    sprite(sheet, SpriteMap.PLAYLIST_TOP_LEFT_SELECTED, 0, 0)
    val tile = SpriteMap.PLAYLIST_TOP_TILE_SELECTED
    val w = layout.width
    val titleW = SpriteMap.PLAYLIST_TITLE_BAR_SELECTED.w
    // webamp's row: corner, a 12px spacer, fill, the title plate, a 13px spacer, fill,
    // corner, with the two spacers only when the width index is even
    // (PlaylistWindow/index.tsx: showSpacers = size[0] % 2 === 0)
    val spacers = layout.cols % 2 == 0
    val leftSpacer = if (spacers) 12 else 0
    val rightSpacer = if (spacers) 13 else 0
    val slack = w - 50 - titleW - leftSpacer - rightSpacer
    val leftFill = slack / 2
    if (leftSpacer > 0) sprite(sheet, Sprite(tile.x, tile.y, leftSpacer, tile.h), 25, 0)
    fillTiles(sheet, tile, 25 + leftSpacer, 25 + leftSpacer + leftFill)
    val titleX = 25 + leftSpacer + leftFill
    sprite(sheet, SpriteMap.PLAYLIST_TITLE_BAR_SELECTED, titleX, 0)
    if (rightSpacer > 0) sprite(sheet, Sprite(tile.x, tile.y, rightSpacer, tile.h), titleX + titleW, 0)
    fillTiles(sheet, tile, titleX + titleW + rightSpacer, w - 25)
    sprite(sheet, SpriteMap.PLAYLIST_TOP_RIGHT_CORNER_SELECTED, w - 25, 0)

    if (s.pressedWidget == "pl.close") sprite(sheet, SpriteMap.PLAYLIST_CLOSE_SELECTED, w - 11, Dest.PL_CLOSE.y)
    if (s.pressedWidget == "pl.shade") sprite(sheet, SpriteMap.PLAYLIST_COLLAPSE_SELECTED, w - 21, Dest.PL_SHADE.y)

    // --- middle: side tiles + text area ---
    var ty = Dest.PL_TOP_H
    while (ty < h - Dest.PL_BOTTOM_H) {
        val tileH = minOf(Dest.PL_TILE_STEP, h - Dest.PL_BOTTOM_H - ty)
        val left = SpriteMap.PLAYLIST_LEFT_TILE
        val right = SpriteMap.PLAYLIST_RIGHT_TILE
        sprite(sheet, Sprite(left.x, left.y, left.w, tileH), 0, ty)
        sprite(sheet, Sprite(right.x, right.y, right.w, tileH), w - Dest.PL_RIGHT_W, ty)
        ty += tileH
    }
    drawRect(
        style.normalBg,
        Offset(layout.textLeft.toFloat(), Dest.PL_TOP_H.toFloat()),
        Size((layout.textRight - layout.textLeft).toFloat(), layout.middleH.toFloat()),
    )

    drawTracks(skin, s, layout, textLayer, scale)

    // --- bottom bar: a 125px cap on the left, a 150px cap on the right, and
    // the 25px filler tiled across whatever the extra width opens between them
    // (at 275 they meet exactly and no filler is drawn) ---
    val bottomY = h - Dest.PL_BOTTOM_H
    val rightCap = SpriteMap.PLAYLIST_BOTTOM_RIGHT_CORNER
    sprite(sheet, SpriteMap.PLAYLIST_BOTTOM_LEFT_CORNER, 0, bottomY)
    var bx = SpriteMap.PLAYLIST_BOTTOM_LEFT_CORNER.w
    while (bx < w - rightCap.w) {
        val piece = minOf(SpriteMap.PLAYLIST_BOTTOM_TILE.w, w - rightCap.w - bx)
        val fill = SpriteMap.PLAYLIST_BOTTOM_TILE
        sprite(sheet, Sprite(fill.x, fill.y, piece, fill.h), bx, bottomY)
        bx += piece
    }
    sprite(sheet, rightCap, w - rightCap.w, bottomY)

    // Running time (elapsed/total) and the mini time, inside the plates the bottom-right
    // corner draws for them. The positions are webamp's (.playlist-running-time-display
    // top:10, .mini-time top:23, left 7 and 66 of the 150px corner).
    val textSheet = skin[Sheet.TEXT]
    val totalSec = s.playlist.sumOf { it.durationSec }
    val runningTime = "${formatTime(s.currentTimeSec)}/${formatTime(totalSec)}"
    bitmapText(textSheet, runningTime, layout.rightCapX + 7, h - Dest.PL_BOTTOM_H + 10)
    if (s.transport != Transport.Stopped && (s.transport != Transport.Paused || s.blinkOn)) {
        bitmapText(textSheet, formatTime(s.currentTimeSec), layout.rightCapX + 66, h - Dest.PL_BOTTOM_H + 23)
    }

    // --- scrollbar handle ---
    val maxScroll = (s.playlist.size - layout.visibleRows).coerceAtLeast(0)
    val frac = if (maxScroll == 0) 0f else s.playlistScroll.toFloat() / maxScroll
    val handleY = layout.scrollTrackTop + (frac * layout.scrollTrackH).roundToInt()
    val handle =
        if (s.pressedWidget == "pl.scroll") {
            SpriteMap.PLAYLIST_SCROLL_HANDLE_SELECTED
        } else {
            SpriteMap.PLAYLIST_SCROLL_HANDLE
        }
    sprite(sheet, handle, layout.scrollX, handleY)

    // --- expanded bottom-bar menu, drawn over everything ---
    val open = playlistMenus(width = layout.width).find { it.id == s.openMenu } ?: return
    val topY = open.topY(h)
    sprite(sheet, open.bar, open.buttonX - PlaylistMenu.BAR_W, topY)
    open.entries.forEachIndexed { i, entry ->
        val art = if (s.pressedWidget == entry.id) entry.selected else entry.normal
        sprite(sheet, art, open.buttonX, topY + i * PlaylistMenu.ENTRY_H)
    }
}

private fun DrawScope.drawTracks(
    skin: Skin,
    s: WinampState,
    layout: PlaylistLayout,
    textLayer: PlaylistTextRasterizer,
    scale: Int,
) {
    val style = skin.pledit
    clipRect(
        layout.textLeft.toFloat(),
        Dest.PL_TOP_H.toFloat(),
        layout.textRight.toFloat(),
        (Dest.PL_TOP_H + layout.middleH).toFloat(),
    ) {
        // selection bands under the text, in plain virtual-space rects
        for (visRow in 0 until layout.visibleRows + 1) {
            val index = s.playlistScroll + visRow
            if (index >= s.playlist.size) break
            if (index in s.selectedRows) {
                val rowY = layout.textTop + visRow * Dest.PL_ROW_H
                drawRect(
                    style.selectedBg,
                    Offset(layout.textLeft.toFloat(), rowY.toFloat()),
                    Size((layout.textRight - layout.textLeft).toFloat(), Dest.PL_ROW_H.toFloat()),
                )
            }
        }
        // text pre-rasterized at device resolution; the virtual dst size makes the
        // canvas transform map it 1:1 to device pixels, so glyphs stay crisp
        val layer = textLayer.textLayer(s, layout, style, scale)
        drawImage(
            image = layer,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(layer.width, layer.height),
            dstOffset = IntOffset(layout.textLeft, Dest.PL_TOP_H),
            dstSize = IntSize(layout.textRight - layout.textLeft, layout.middleH),
            filterQuality = FilterQuality.None,
        )
    }
}

/**
 * The rows: a press selects, a second tap on the same row plays, a drag scrolls or flings,
 * and a long press opens the row menu.
 */
private fun playlistRows(
    vm: WindowControls,
    s: WinampState,
    layout: PlaylistLayout,
    rowMenu: (Int) -> AmpMenu? = { null },
): Widget =
    rowListWidget(
        "pl.rows",
        androidx.compose.ui.unit
            .IntRect(layout.textLeft, Dest.PL_TOP_H, layout.textRight, Dest.PL_TOP_H + layout.middleH),
        s,
        Dest.PL_ROW_H,
        rowAt = { y -> ((y - layout.textTop) / Dest.PL_ROW_H).toInt() },
        rows = { s.playlist.size },
        visibleRows = { layout.visibleRows },
        scroll = { s.playlistScroll },
        scrollTo = { s.playlistScroll = it },
        onPress = { row, _ ->
            // as in Winamp, selection happens on the press; a press on the empty area
            // under the last row clears the selection
            if (row in s.playlist.indices) vm.selectTrack(row) else vm.selectZero()
        },
        onRelease = { row, tapped ->
            val now = System.currentTimeMillis()
            if (tapped && row in s.playlist.indices) {
                if (row == s.plTapRow && now - s.plTapAt < DOUBLE_TAP_MS) vm.playTrack(row)
                s.plTapRow = row
                s.plTapAt = now
            } else {
                s.plTapRow = -1
            }
        },
        onLongPress = { row ->
            // the menu acts on the selection, so a row outside it is selected first
            if (row in s.playlist.indices && row !in s.selectedRows) vm.selectTrack(row)
            rowMenu(row)?.let { s.activeMenu = it }
        },
    )

/** A second tap on the same row within this many milliseconds plays it. */
private const val DOUBLE_TAP_MS = 400
