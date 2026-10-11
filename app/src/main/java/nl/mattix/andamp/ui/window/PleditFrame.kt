// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.Sprite
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.ui.bitmapText
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.spriteRow

/**
 * A floating window frame built from the playlist window's art (PLEDIT.BMP), for skins
 * without a GEN.BMP of their own; see [frameFor].
 *
 * The title is drawn with the 5x6 bitmap font over filler tiles, because the playlist's own
 * title art has "WINAMP PLAYLIST" in it.
 */
object PleditFrame {
    const val TITLE_H = 20
    const val BOTTOM_H = 14
    const val LEFT_W = 12
    const val RIGHT_W = 20
    private const val CORNER_W = 25
    private const val TILE_H = 29
    private const val TITLE_TEXT_Y = 7

    /** The grip art's inset from the window's right and bottom edges. */
    private const val GRIP_INSET_RIGHT = 3
    private const val GRIP_INSET_BOTTOM = 2

    const val CHROME_W = LEFT_W + RIGHT_W
    const val CHROME_H = TITLE_H + BOTTOM_H

    fun contentWidth(windowW: Int) = windowW - CHROME_W

    fun contentHeight(windowH: Int) = windowH - CHROME_H

    /** The close button's x, the same place as in the playlist window. */
    fun closeX(windowW: Int) = windowW - 2 - CLOSE_W

    const val CLOSE_W = 9
    const val CLOSE_INSET_TOP = 3

    fun DrawScope.drawPleditFrame(
        skin: Skin,
        windowW: Int,
        windowH: Int,
        title: String,
        closePressed: Boolean = false,
    ) {
        val sheet = skin[Sheet.PLEDIT]
        drawTitleBar(sheet, skin, windowW, title)
        drawSides(sheet, windowW, windowH)
        drawThinBottom(skin, windowW, windowH)
        // the unpressed X is part of the corner art; only the lit one is a sprite
        if (closePressed) {
            sprite(sheet, SpriteMap.PLAYLIST_CLOSE_SELECTED, closeX(windowW), CLOSE_INSET_TOP)
        }
    }

    private fun DrawScope.drawTitleBar(
        sheet: ImageBitmap,
        skin: Skin,
        windowW: Int,
        title: String,
    ) {
        val tile = SpriteMap.PLAYLIST_TOP_TILE_SELECTED
        clipRect(0f, 0f, windowW.toFloat(), TITLE_H.toFloat()) {
            var x = 0
            while (x < windowW) {
                sprite(sheet, tile, x, 0)
                x += tile.w
            }
        }
        sprite(sheet, SpriteMap.PLAYLIST_TOP_LEFT_SELECTED, 0, 0)
        sprite(sheet, SpriteMap.PLAYLIST_TOP_RIGHT_CORNER_SELECTED, windowW - CORNER_W, 0)
        // Centered. TEXT.BMP glyph cells have a black background, so they are blended
        // lighten-only and the title bar's texture shows around the letters.
        val textW = title.length * BitmapFont.CHAR_W
        bitmapText(skin[Sheet.TEXT], title, (windowW - textW) / 2, TITLE_TEXT_Y, BlendMode.Lighten)
    }

    private fun DrawScope.drawSides(
        sheet: ImageBitmap,
        windowW: Int,
        windowH: Int,
    ) {
        val middleH = windowH - CHROME_H
        if (middleH <= 0) return
        val rightX = windowW - RIGHT_W
        clipRect(0f, TITLE_H.toFloat(), windowW.toFloat(), (TITLE_H + middleH).toFloat()) {
            var y = TITLE_H
            while (y < TITLE_H + middleH) {
                sprite(sheet, SpriteMap.PLAYLIST_LEFT_TILE, 0, y)
                sprite(sheet, SpriteMap.PLAYLIST_RIGHT_TILE, rightX, y)
                y += TILE_H
            }
        }
    }

    /**
     * A thin bottom border, [BOTTOM_H] high, along the bottom of a window [windowW] by
     * [windowH]. The playlist's own 38px bar has its buttons and time display in the art, so
     * this uses the border line from the top of that art and its outer edge, with the skin's
     * resize grip unless the window cannot be resized ([grip] false).
     *
     * With [corners], the two ends of the border are the ends of the bar's own corner pieces,
     * as wide as the side tiles above them. A skin whose side borders differ from the inside
     * of its bar then has them run on down to the bottom edge, and its scrollbar's groove
     * ends as it does in the playlist window. What a skin draws in those ends comes with
     * them, the lowest lines of its grip included.
     */
    fun DrawScope.drawThinBottom(
        skin: Skin,
        windowW: Int,
        windowH: Int,
        grip: Boolean = true,
        corners: Boolean = false,
    ) {
        val sheet = skin[Sheet.PLEDIT]
        val top = SpriteMap.PLAYLIST_BOTTOM_BORDER_TOP
        val edge = SpriteMap.PLAYLIST_BOTTOM_BORDER_EDGE
        val topY = windowH - BOTTOM_H
        val edgeY = windowH - edge.h
        clipRect(0f, topY.toFloat(), windowW.toFloat(), windowH.toFloat()) {
            spriteRow(sheet, top, 0, topY, windowW)
            spriteRow(sheet, edge, 0, edgeY, windowW)
            if (corners) {
                val left = SpriteMap.PLAYLIST_BOTTOM_LEFT_CORNER
                val right = SpriteMap.PLAYLIST_BOTTOM_RIGHT_CORNER
                val rightX = right.x + right.w - RIGHT_W
                // the same two bands of rows as the middle: the top of the bar and its outer edge
                sprite(sheet, Sprite(left.x, left.y, LEFT_W, top.h), 0, topY)
                sprite(sheet, Sprite(left.x, left.y + left.h - edge.h, LEFT_W, edge.h), 0, edgeY)
                sprite(sheet, Sprite(rightX, right.y, RIGHT_W, top.h), windowW - RIGHT_W, topY)
                sprite(sheet, Sprite(rightX, right.y + right.h - edge.h, RIGHT_W, edge.h), windowW - RIGHT_W, edgeY)
            }
            if (grip) {
                val art = SpriteMap.PLAYLIST_RESIZE_GRIP
                sprite(sheet, art, windowW - art.w - GRIP_INSET_RIGHT, windowH - art.h - GRIP_INSET_BOTTOM)
            }
        }
    }
}
