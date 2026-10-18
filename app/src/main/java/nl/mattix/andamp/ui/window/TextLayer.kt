// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.GlyphMask
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A transparent layer of words over a list window's own bands, in the skin's font where it can
 * spell them and in the outline font where it cannot.
 *
 * The window draws the backgrounds and the selection bands itself, under the layer. Words in the
 * skin's font are written one pixel to the virtual pixel into a pixel block, which the window
 * draws at its scale unfiltered, as the playlist does ([PlaylistTextRasterizer]). When any piece
 * needs the outline font, the layer is made at device resolution, with the block drawn into it
 * first.
 *
 * One instance makes one layer over and over, and keeps its pixel block between them.
 */
internal class TextLayer {
    /** The pixel block the skin's font is written into, kept between layers of one size. */
    private var block = IntArray(0)

    /**
     * A layer [width] by [height] virtual pixels, at [scale] device pixels to the virtual pixel,
     * or at one when every piece is in the skin's font. [glyphs] is the skin's font, or null for
     * the outline font throughout; [paint] carries the outline font.
     */
    fun draw(
        width: Int,
        height: Int,
        scale: Int,
        glyphs: GlyphMask?,
        paint: Paint,
        write: Words.() -> Unit,
    ): Bitmap {
        val words = Words(glyphs).apply(write)
        val (spelled, outlined) = words.pieces.partition { it.skin }
        val pixels = glyphs?.takeIf { spelled.isNotEmpty() }?.let { spell(spelled, it, width, height) }
        if (pixels != null && outlined.isEmpty()) return pixels
        val bitmap =
            Bitmap.createBitmap(
                (width * scale).coerceAtLeast(1),
                (height * scale).coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        pixels?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), UNFILTERED) }
        for (piece in outlined) outline(piece, canvas, paint)
        return bitmap
    }

    private fun spell(
        pieces: List<Piece>,
        glyphs: GlyphMask,
        width: Int,
        height: Int,
    ): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        // the bitmap made below copies the block, so the next layer can write over it
        if (block.size == w * h) block.fill(0) else block = IntArray(w * h)
        for (piece in pieces) piece.spell(glyphs, block, w)
        return Bitmap.createBitmap(block, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun outline(
        piece: Piece,
        canvas: Canvas,
        paint: Paint,
    ) {
        paint.color = piece.argb
        piece.outline(canvas, paint)
        paint.textScaleX = 1f
    }

    /** Where a label sits in its room. */
    enum class Align { LEFT, CENTER, RIGHT }

    /** What a label too wide for its room does in the outline font; in the skin's font it is cut. */
    enum class Fit {
        /** Clipped at the room's edge. */
        CLIP,

        /** Squeezed sideways until it fits, as a translated label longer than the English is. */
        SQUEEZE,
    }

    /**
     * The pieces of one layer, in virtual pixels. Each is in the skin's font when there is one
     * and it can spell the piece's text.
     */
    class Words internal constructor(
        private val glyphs: GlyphMask?,
    ) {
        internal val pieces = mutableListOf<Piece>()

        private fun spells(text: String) = glyphs != null && BitmapFont.covers(text)

        /** [text] in the room from [left] to [right] on the line from [top], [height] tall. */
        @Suppress("LongParameterList") // the words, their room and how they sit in it
        fun label(
            text: String,
            left: Float,
            right: Float,
            top: Int,
            height: Int,
            argb: Int,
            align: Align = Align.LEFT,
            fit: Fit = Fit.CLIP,
        ) {
            pieces += Label(text, left, right, top, height, argb, spells(text), align, fit)
        }

        /** A list row: [label] from [left], cut short of [detail], which stands against [right]. */
        @Suppress("LongParameterList") // the row's words and its room
        fun row(
            label: String,
            detail: String,
            left: Float,
            right: Float,
            top: Int,
            height: Int,
            argb: Int,
        ) {
            pieces += Row(label, detail, left, right, top, height, argb, spells(label + detail))
        }

        /**
         * A text field: [query] from [left], kept in view at its end when it outgrows [right],
         * with a caret after the [caret]th character while [caretOn].
         */
        @Suppress("LongParameterList") // the field's text, its caret and its room
        fun field(
            query: String,
            caret: Int,
            caretOn: Boolean,
            left: Float,
            right: Float,
            top: Int,
            height: Int,
            argb: Int,
        ) {
            pieces += Field(query, caret, caretOn, left, right, top, height, argb, spells(query))
        }

        /** An x in the middle of the square cell [size] wide at ([left], [top]). */
        fun cross(
            left: Int,
            top: Int,
            size: Int,
            argb: Int,
        ) {
            pieces += Cross(left, top, size, argb, glyphs != null)
        }
    }

    /** One thing written into the layer. */
    internal sealed class Piece(
        val argb: Int,
        /** Written in the skin's font; otherwise in the outline font. */
        val skin: Boolean,
    ) {
        abstract fun spell(
            glyphs: GlyphMask,
            block: IntArray,
            stride: Int,
        )

        abstract fun outline(
            canvas: Canvas,
            paint: Paint,
        )
    }

    private class Label(
        val text: String,
        val left: Float,
        val right: Float,
        val top: Int,
        val height: Int,
        argb: Int,
        skin: Boolean,
        val align: Align,
        val fit: Fit,
    ) : Piece(argb, skin) {
        override fun spell(
            glyphs: GlyphMask,
            block: IntArray,
            stride: Int,
        ) {
            val room = right - left
            val shown = BitmapFont.fitted(text, (room / BitmapFont.CHAR_W).toInt())
            val w = shown.length * BitmapFont.CHAR_W
            val x =
                when (align) {
                    Align.LEFT -> left
                    Align.CENTER -> left + (room - w) / 2f
                    Align.RIGHT -> right - w
                }
            glyphs.write(shown, x.roundToInt(), letterTop(top, height), argb, block, stride, right.roundToInt())
        }

        override fun outline(
            canvas: Canvas,
            paint: Paint,
        ) {
            val room = right - left
            val natural = paint.measureText(text)
            if (fit == Fit.SQUEEZE) paint.textScaleX = ListText.squeeze(natural, room)
            val w = natural * paint.textScaleX
            val x =
                when (align) {
                    Align.LEFT -> left
                    Align.CENTER -> left + (room - w) / 2f
                    Align.RIGHT -> right - w
                }
            canvas.save()
            canvas.clipRect(min(x, left), top.toFloat(), right, (top + height).toFloat())
            canvas.drawText(text, x, ListText.baseline(paint, top.toFloat(), height), paint)
            canvas.restore()
        }
    }

    private class Row(
        val label: String,
        val detail: String,
        val left: Float,
        val right: Float,
        val top: Int,
        val height: Int,
        argb: Int,
        skin: Boolean,
    ) : Piece(argb, skin) {
        override fun spell(
            glyphs: GlyphMask,
            block: IntArray,
            stride: Int,
        ) {
            val y = letterTop(top, height)
            var labelRight = right.toInt()
            if (detail.isNotBlank()) {
                val shown = BitmapFont.normalize(detail)
                val x = labelRight - shown.length * BitmapFont.CHAR_W
                glyphs.write(shown, x, y, argb, block, stride)
                labelRight = x - DETAIL_GAP.toInt()
            }
            val l = left.toInt()
            val room = (labelRight - l) / BitmapFont.CHAR_W
            glyphs.write(BitmapFont.fitted(label, room), l, y, argb, block, stride, labelRight)
        }

        override fun outline(
            canvas: Canvas,
            paint: Paint,
        ) {
            val baseline = ListText.baseline(paint, top.toFloat(), height)
            var labelRight = right
            if (detail.isNotBlank()) {
                val w = paint.measureText(detail)
                canvas.drawText(detail, labelRight - w, baseline, paint)
                labelRight -= w + DETAIL_GAP
            }
            canvas.save()
            canvas.clipRect(0f, top.toFloat(), labelRight, (top + height).toFloat())
            canvas.drawText(label, left, baseline, paint)
            canvas.restore()
        }
    }

    private class Field(
        val query: String,
        val caret: Int,
        val caretOn: Boolean,
        val left: Float,
        val right: Float,
        val top: Int,
        val height: Int,
        argb: Int,
        skin: Boolean,
    ) : Piece(argb, skin) {
        override fun spell(
            glyphs: GlyphMask,
            block: IntArray,
            stride: Int,
        ) {
            val shown = BitmapFont.normalize(query)
            val y = letterTop(top, height)
            val x = min(left.toInt(), right.toInt() - shown.length * BitmapFont.CHAR_W - CARET_W)
            glyphs.write(shown, x, y, argb, block, stride, right.toInt())
            if (!caretOn) return
            val caretX = x + caret.coerceIn(0, shown.length) * BitmapFont.CHAR_W
            if (caretX !in 0 until right.toInt()) return
            // as tall as a letter, with a pixel to spare above it
            for (row in (y - 1).coerceAtLeast(0) until (y + BitmapFont.CHAR_H).coerceAtMost(block.size / stride)) {
                block[row * stride + caretX] = argb
            }
        }

        override fun outline(
            canvas: Canvas,
            paint: Paint,
        ) {
            val baseline = ListText.baseline(paint, top.toFloat(), height)
            // when the query outgrows the field, its end stays visible
            val x = min(left, right - paint.measureText(query) - CARET_W)
            canvas.save()
            canvas.clipRect(0f, top.toFloat(), right, (top + height).toFloat())
            canvas.drawText(query, x, baseline, paint)
            if (caretOn) {
                val caretX = x + paint.measureText(query.substring(0, caret.coerceIn(0, query.length)))
                canvas.drawRect(caretX, top + CARET_INSET, caretX + CARET_W, top + height - CARET_INSET, paint)
            }
            canvas.restore()
        }
    }

    private class Cross(
        val left: Int,
        val top: Int,
        val size: Int,
        argb: Int,
        skin: Boolean,
    ) : Piece(argb, skin) {
        override fun spell(
            glyphs: GlyphMask,
            block: IntArray,
            stride: Int,
        ) {
            val x = left + (size - BitmapFont.CHAR_W + 1) / 2
            glyphs.write("x", x, letterTop(top, size), argb, block, stride)
        }

        override fun outline(
            canvas: Canvas,
            paint: Paint,
        ) {
            // an x as two lines, as wide as a letter, in the middle of the cell
            val middleX = left + size / 2f
            val middleY = top + size / 2f
            val half = CROSS_HALF
            canvas.drawLine(middleX - half, middleY - half, middleX + half, middleY + half, paint)
            canvas.drawLine(middleX - half, middleY + half, middleX + half, middleY - half, paint)
        }
    }

    private companion object {
        /** A row's label stops this far short of its detail. */
        const val DETAIL_GAP = 6f

        const val CARET_W = 1

        /** The outline font's caret stops this far short of the top and the bottom of its line. */
        const val CARET_INSET = 2f

        /** Half the width of the outline font's x. */
        const val CROSS_HALF = 2.5f

        /** Skin art is scaled without filtering, so a pixel stays a square. */
        val UNFILTERED = Paint().apply { isFilterBitmap = false }

        /** The top of a skin-font letter in a line [height] tall at [top]: a 5 px letter over a blank row, centered. */
        fun letterTop(
            top: Int,
            height: Int,
        ) = top + (height - BitmapFont.CHAR_H + 1) / 2
    }
}
