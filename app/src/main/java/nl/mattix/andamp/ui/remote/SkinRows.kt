// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.GlyphMask
import nl.mattix.andamp.ui.window.PlaylistTextRasterizer
import kotlin.math.roundToInt

/**
 * The rows of the list on a lent surface in the skin's font, the 5x6 letters of its TEXT.BMP
 * in the colors the rows have, a pixel of the font to a pixel of the window: the size of the
 * song title in the main window.
 *
 * A row's words are written once into a picture, from the play button to the frame, and kept
 * while they are drawn. [draw] puts the picture on the window's canvas, [scale] to one of the
 * window's pixels, unfiltered and on a whole pixel of the canvas, so all of a letter's pixels
 * are one size.
 */
internal class SkinRows(
    private val glyphs: GlyphMask,
    private val layout: RemoteListLayout,
    private val scale: Int,
) {
    /** How wide a row's picture is. */
    private val width = layout.listRight - layout.playRight

    private val made =
        object : LinkedHashMap<RowWords, Bitmap>(KEPT, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RowWords, Bitmap>) = size > KEPT
        }

    /** Whether the skin's font has a letter for every letter of [title]; a row it has not keeps the regular font. */
    fun spell(title: String): Boolean = BitmapFont.covers(title)

    /** Draws the row whose top is at [top], in the window's pixels, on [canvas], which works in those pixels. */
    @Suppress("LongParameterList") // a row's words and color, and where they go
    fun draw(
        canvas: Canvas,
        title: String,
        length: String,
        argb: Int,
        top: Float,
    ) {
        val picture = made.getOrPut(RowWords(title, length, argb)) { written(title, length, argb) }
        // the letter, five of the cell's six rows, in the middle of the row and on a whole
        // pixel of the canvas
        val y = ((top + (layout.rowH - LETTER_H) / 2f) * scale).roundToInt().toFloat() / scale
        val x = layout.playRight.toFloat()
        canvas.drawBitmap(picture, null, RectF(x, y, x + width, y + BitmapFont.CHAR_H), UNFILTERED)
    }

    private fun written(
        title: String,
        length: String,
        argb: Int,
    ): Bitmap {
        val block = IntArray(width * BitmapFont.CHAR_H)
        PlaylistTextRasterizer.writeRow(glyphs, title, length, argb, 0, block, width)
        return Bitmap.createBitmap(block, width, BitmapFont.CHAR_H, Bitmap.Config.ARGB_8888)
    }

    private data class RowWords(
        val title: String,
        val length: String,
        val argb: Int,
    )

    private companion object {
        /** How many rows' pictures are kept: more than a screen shows, so a scroll back finds them made. */
        const val KEPT = 64
        const val LOAD = 0.75f

        const val LETTER_H = BitmapFont.CHAR_H - 1

        /** Skin art is scaled without filtering, so a pixel stays a square. */
        val UNFILTERED = Paint().apply { isFilterBitmap = false }
    }
}
