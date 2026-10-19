// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.GlyphMask
import nl.mattix.andamp.state.SourceMark

/**
 * Draws the marks of sources ([SourceMark]) beside a list row's words, in the row's color: as
 * pixel art in the skin's font, a pixel to the virtual pixel like its letters, and as a vector
 * path or a shape in the outline font, as tall as its letters, so they scale with the text. A
 * mark without the form the font wants is drawn as its letter.
 *
 * Each mark sits in the middle of a cell as wide as the widest of its peers, the marks of every
 * source shown, so the words beside marks of different widths line up.
 */
internal object MarkInk {
    /** The room between two marks, in virtual pixels. */
    private const val GAP = 1

    /** How tall a vector mark is against the outline font's size. */
    private const val SIZE_OF_TEXT = 0.9f

    /** The paths parsed so far, by their data; a path that does not parse is the letter. */
    private val parsed = HashMap<String, Path?>()

    /** Draws a shape ([SourceMark.mask]) smoothly in the color it is given. */
    private val shapes = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** How wide one mark is in the skin's font: its pixel art, or its letter. */
    private fun pixelWidthOf(mark: SourceMark): Int = if (mark.pixelWidth > 0) mark.pixelWidth else BitmapFont.CHAR_W

    /** How wide [marks] are in the skin's font, each in a cell as wide as the widest of [peers]. */
    fun pixelWidth(
        marks: List<SourceMark>,
        peers: Collection<SourceMark> = marks,
    ): Int {
        if (marks.isEmpty()) return 0
        val cell = peers.maxOfOrNull(::pixelWidthOf) ?: 0
        return marks.sumOf { maxOf(cell, pixelWidthOf(it)) } + GAP * (marks.size - 1)
    }

    /** Writes [marks] into [block] from [x], centered on letters whose tops are at [y], in [argb]; see [pixelWidth]. */
    @Suppress("LongParameterList") // the marks, where they go, and the block they go into
    fun spell(
        marks: List<SourceMark>,
        x: Int,
        y: Int,
        argb: Int,
        glyphs: GlyphMask,
        block: IntArray,
        stride: Int,
        peers: Collection<SourceMark> = marks,
    ) {
        val cell = peers.maxOfOrNull(::pixelWidthOf) ?: 0
        var at = x
        for (mark in marks) {
            val own = pixelWidthOf(mark)
            val slot = maxOf(cell, own)
            val from = at + (slot - own) / 2
            if (mark.pixelWidth == 0) {
                glyphs.write(BitmapFont.normalize(mark.letter), from, y, argb, block, stride)
            } else {
                stamp(mark, from, y, argb, block, stride)
            }
            at += slot + GAP
        }
    }

    /** One mark's pixel art into [block], from [x], centered on the letters whose tops are at [y]. */
    @Suppress("LongParameterList") // the mark, where it goes, and the block it goes into
    private fun stamp(
        mark: SourceMark,
        x: Int,
        y: Int,
        argb: Int,
        block: IntArray,
        stride: Int,
    ) {
        val rows = block.size / stride
        val top = y + (SourceMark.LETTER_H - mark.pixels.size) / 2
        for (py in mark.pixels.indices) {
            val by = top + py
            if (by !in 0 until rows) continue
            for (px in 0 until mark.pixelWidth) {
                val bx = x + px
                if (mark.ink(px, py) && bx in 0 until stride) block[by * stride + bx] = argb
            }
        }
    }

    /** How wide [marks] are in the outline font of [paint], each in a cell as wide as the widest of [peers]. */
    fun outlineWidth(
        marks: List<SourceMark>,
        paint: Paint,
        peers: Collection<SourceMark> = marks,
    ): Float {
        if (marks.isEmpty()) return 0f
        val size = paint.textSize * SIZE_OF_TEXT
        val cell = peers.maxOfOrNull { widthOf(it, size, paint) } ?: 0f
        return marks.sumOf { mark -> maxOf(cell, widthOf(mark, size, paint)).toDouble() }.toFloat() + GAP * (marks.size - 1)
    }

    /** How wide one mark is in the outline font, its picture [size] tall. */
    private fun widthOf(
        mark: SourceMark,
        size: Float,
        paint: Paint,
    ): Float {
        val mask = mark.mask
        return when {
            mask != null -> size * mask.width / mask.height
            pathOf(mark) != null -> size
            else -> paint.measureText(mark.letter)
        }
    }

    /** Draws [marks] from [x], in the middle of a line [height] tall at [top], in [paint]'s color; see [outlineWidth]. */
    @Suppress("LongParameterList") // the marks, where they go, and what draws them
    fun outline(
        marks: List<SourceMark>,
        x: Float,
        top: Float,
        height: Int,
        canvas: Canvas,
        paint: Paint,
        peers: Collection<SourceMark> = marks,
    ) {
        val size = paint.textSize * SIZE_OF_TEXT
        val cell = peers.maxOfOrNull { widthOf(it, size, paint) } ?: 0f
        var at = x
        for (mark in marks) {
            val own = widthOf(mark, size, paint)
            val slot = maxOf(cell, own)
            draw(mark, at + (slot - own) / 2f, top, height, size, canvas, paint)
            at += slot + GAP
        }
    }

    /** One mark from [x], [size] tall in the middle of a line [height] tall at [top]. */
    @Suppress("LongParameterList") // the mark, where it goes, and what draws it
    private fun draw(
        mark: SourceMark,
        x: Float,
        top: Float,
        height: Int,
        size: Float,
        canvas: Canvas,
        paint: Paint,
    ) {
        val mask = mark.mask
        val path = pathOf(mark)
        when {
            mask != null -> {
                val y = top + (height - size) / 2f
                shapes.color = paint.color
                canvas.drawBitmap(mask, null, RectF(x, y, x + widthOf(mark, size, paint), y + size), shapes)
            }

            path == null -> {
                canvas.drawText(mark.letter, x, ListText.baseline(paint, top, height), paint)
            }

            else -> {
                canvas.save()
                canvas.translate(x, top + (height - size) / 2f)
                canvas.scale(size / SourceMark.VIEWPORT, size / SourceMark.VIEWPORT)
                val style = paint.style
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
                paint.style = style
                canvas.restore()
            }
        }
    }

    private fun pathOf(mark: SourceMark): Path? {
        val data = mark.path ?: return null
        return synchronized(parsed) {
            parsed.getOrPut(data) {
                runCatching { PathParser.createPathFromPathData(data) }
                    .getOrNull()
                    ?.apply { fillType = Path.FillType.EVEN_ODD }
            }
        }
    }
}
