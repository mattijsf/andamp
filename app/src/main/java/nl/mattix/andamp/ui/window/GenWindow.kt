// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import nl.mattix.andamp.skin.GenTitleFont
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.ui.sprite

/**
 * Winamp's generic window frame (GEN.BMP) — the chrome it wrapped around its
 * non-player windows, and what webamp wraps its Milkdrop window in.
 *
 * Layout, from webamp's gen-window.css: a 20px titlebar of
 * corner(25) + fill + end(25) + title + end(25) + fill + corner(25) with the
 * 9x9 close button inset 2 from the right and 3 from the top; 11px and 8px
 * side borders whose bottom 24px use their own sprites; a 14px bottom of
 * left(125) and right(125) over a tiled fill.
 */
object GenWindow {
    const val TITLE_H = 20
    const val BOTTOM_H = 14
    const val LEFT_W = 11
    const val RIGHT_W = 8
    const val CORNER_W = 25
    const val BOTTOM_CAP_W = 125
    const val CLOSE_W = 9
    const val CLOSE_INSET_RIGHT = 2
    const val CLOSE_INSET_TOP = 3

    /**
     * The row the 7px title glyphs start on inside the 20px bar. It centers them on the
     * rails beside the title, which occupy rows 5..9 in GEN.BMP; webamp's 2px margin puts
     * them two rows higher.
     */
    const val TITLE_TOP = 4
    private const val SIDE_BOTTOM_H = 24

    /** Chrome eaten by the frame, so callers can size the content area. */
    const val CHROME_W = LEFT_W + RIGHT_W
    const val CHROME_H = TITLE_H + BOTTOM_H

    fun contentWidth(windowW: Int) = windowW - CHROME_W

    fun contentHeight(windowH: Int) = windowH - CHROME_H

    fun closeX(windowW: Int) = windowW - CLOSE_INSET_RIGHT - CLOSE_W

    /**
     * Draws the frame for a [windowW] x [windowH] window at the origin. [focused] picks the
     * lit or dim sprite row.
     */
    fun DrawScope.drawGenFrame(
        skin: Skin,
        windowW: Int,
        windowH: Int,
        title: String,
        focused: Boolean = true,
        closePressed: Boolean = false,
    ) {
        val sheet = skin.getOrNull(Sheet.GEN) ?: return
        drawTitleBar(sheet, skin.genTitleFont, windowW, title, focused)
        drawSides(sheet, windowW, windowH)
        drawBottom(sheet, windowW, windowH)
        // the unpressed X is part of the corner art; the lit sprite covers it while held
        if (closePressed) {
            sprite(sheet, SpriteMap.GEN_CLOSE_SELECTED, closeX(windowW), CLOSE_INSET_TOP)
        }
    }

    private fun DrawScope.drawTitleBar(
        sheet: ImageBitmap,
        font: GenTitleFont?,
        windowW: Int,
        title: String,
        focused: Boolean,
    ) {
        val left = if (focused) SpriteMap.GEN_TOP_LEFT_SELECTED else SpriteMap.GEN_TOP_LEFT
        val leftEnd = if (focused) SpriteMap.GEN_TOP_LEFT_END_SELECTED else SpriteMap.GEN_TOP_LEFT_END
        val rightEnd = if (focused) SpriteMap.GEN_TOP_RIGHT_END_SELECTED else SpriteMap.GEN_TOP_RIGHT_END
        val right = if (focused) SpriteMap.GEN_TOP_RIGHT_SELECTED else SpriteMap.GEN_TOP_RIGHT
        val fill = if (focused) SpriteMap.GEN_TOP_LEFT_RIGHT_FILL_SELECTED else SpriteMap.GEN_TOP_LEFT_RIGHT_FILL
        val centerFill = if (focused) SpriteMap.GEN_TOP_CENTER_FILL_SELECTED else SpriteMap.GEN_TOP_CENTER_FILL

        // GenTitleBar has the geometry; this maps pieces to sprites and tiles the ones
        // that stretch
        val shown = if (font == null) title else GenTitleBar.fit(title, windowW) { font.width(it, focused) }
        val titleW = font?.width(shown, focused) ?: 0
        val segments = GenTitleBar.layout(windowW, titleW)
        segments.forEach { segment ->
            val art =
                when (segment.piece) {
                    GenTitleBar.Piece.LEFT_CORNER -> left
                    GenTitleBar.Piece.LEFT_END -> leftEnd
                    GenTitleBar.Piece.RIGHT_END -> rightEnd
                    GenTitleBar.Piece.RIGHT_CORNER -> right
                    GenTitleBar.Piece.PLATE -> centerFill
                    GenTitleBar.Piece.LEFT_FILL, GenTitleBar.Piece.RIGHT_FILL -> fill
                }
            if (segment.width <= 0) return@forEach
            // caps are drawn once; the plate and the rails tile to their width
            clipRect(segment.x.toFloat(), 0f, segment.right.toFloat(), TITLE_H.toFloat()) {
                var x = segment.x
                do {
                    sprite(sheet, art, x, 0)
                    x += art.w
                } while (x < segment.right)
            }
        }

        if (font != null) drawTitle(sheet, font, shown, GenTitleBar.titleX(windowW, titleW), focused)
    }

    private fun DrawScope.drawTitle(
        sheet: ImageBitmap,
        font: GenTitleFont,
        title: String,
        startX: Int,
        focused: Boolean,
    ) {
        var x = startX
        for (char in title) {
            if (char == ' ') {
                x += GenTitleFont.SPACE_W
                continue
            }
            val glyph = font.sprite(char, focused) ?: continue
            sprite(sheet, glyph, x, TITLE_TOP)
            x += glyph.w
        }
    }

    private fun DrawScope.drawSides(
        sheet: ImageBitmap,
        windowW: Int,
        windowH: Int,
    ) {
        val middleH = windowH - CHROME_H
        if (middleH <= 0) return
        val top = TITLE_H.toFloat()
        val bottom = (TITLE_H + middleH).toFloat()
        val rightX = windowW - RIGHT_W
        clipRect(0f, top, windowW.toFloat(), bottom) {
            var y = TITLE_H
            while (y < TITLE_H + middleH) {
                sprite(sheet, SpriteMap.GEN_MIDDLE_LEFT, 0, y)
                sprite(sheet, SpriteMap.GEN_MIDDLE_RIGHT, rightX, y)
                y += SpriteMap.GEN_MIDDLE_LEFT.h
            }
            // the last 24px of each side has its own art, where the border meets the bottom bar
            val bottomY = TITLE_H + middleH - SIDE_BOTTOM_H
            if (bottomY >= TITLE_H) {
                sprite(sheet, SpriteMap.GEN_MIDDLE_LEFT_BOTTOM, 0, bottomY)
                sprite(sheet, SpriteMap.GEN_MIDDLE_RIGHT_BOTTOM, rightX, bottomY)
            }
        }
    }

    private fun DrawScope.drawBottom(
        sheet: ImageBitmap,
        windowW: Int,
        windowH: Int,
    ) {
        val y = windowH - BOTTOM_H
        val fill = SpriteMap.GEN_BOTTOM_FILL
        clipRect(0f, y.toFloat(), windowW.toFloat(), windowH.toFloat()) {
            var x = 0
            while (x < windowW) {
                sprite(sheet, fill, x, y)
                x += fill.w
            }
        }
        sprite(sheet, SpriteMap.GEN_BOTTOM_LEFT, 0, y)
        sprite(sheet, SpriteMap.GEN_BOTTOM_RIGHT, windowW - BOTTOM_CAP_W, y)
    }
}
