// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import kotlin.math.abs

/**
 * The TEXT.BMP glyphs as ink: how much of each pixel is letter, from 0 for the cell's
 * background to 255 for the letter at its strongest. A skin paints its font in one color;
 * the mask is what lets the same letters be drawn in any other.
 *
 * The background is read from the space glyph, pixel for pixel, so a cell that is shaded
 * from top to bottom still comes out empty. A pixel's ink is how far its color is from the
 * background at its place in the cell, against the farthest any glyph goes.
 */
class GlyphMask private constructor(
    private val ink: IntArray,
    private val width: Int,
    private val height: Int,
) {
    /**
     * Writes [text], already folded by [BitmapFont.normalize], into [into], a block of
     * unpremultiplied ARGB pixels [stride] wide, with its top-left corner at ([x], [y]) and
     * in the color [rgb]. Nothing is written at or beyond [clipRight], or outside the block.
     */
    @Suppress("LongParameterList") // a destination, a place in it and what to write there
    fun write(
        text: String,
        x: Int,
        y: Int,
        rgb: Int,
        into: IntArray,
        stride: Int,
        clipRight: Int = stride,
    ) {
        val rows = into.size / stride
        val right = minOf(clipRight, stride)
        val color = rgb and RGB
        var cellX = x
        for (c in text) {
            if (cellX >= right) return
            if (c != ' ') stamp(BitmapFont.sprite(c), cellX, y, color, into, stride, right, rows)
            cellX += BitmapFont.CHAR_W
        }
    }

    @Suppress("LongParameterList") // write's own, and the bounds it worked out once
    private fun stamp(
        glyph: Sprite,
        x: Int,
        y: Int,
        color: Int,
        into: IntArray,
        stride: Int,
        right: Int,
        rows: Int,
    ) {
        for (gy in 0 until glyph.h) {
            val srcY = glyph.y + gy
            val dstY = y + gy
            if (srcY >= height || dstY !in 0 until rows) continue
            for (gx in 0 until glyph.w) {
                val srcX = glyph.x + gx
                val dstX = x + gx
                if (srcX >= width || dstX !in 0 until right) continue
                val alpha = ink[srcY * width + srcX]
                if (alpha != 0) into[dstY * stride + dstX] = (alpha shl ALPHA_SHIFT) or color
            }
        }
    }

    companion object {
        private const val RGB = 0xFFFFFF
        private const val ALPHA_SHIFT = 24
        private const val OPAQUE = 255

        /**
         * The mask of a text sheet of [width] by [height] ARGB [pixels], or null for a sheet
         * with no letters on it: one color all over.
         */
        fun of(
            pixels: IntArray,
            width: Int,
            height: Int,
        ): GlyphMask? {
            if (width <= 0 || height <= 0 || pixels.size < width * height) return null
            val paper = paperOf(pixels, width, height)
            val ink = IntArray(width * height)
            var strongest = 0
            for (glyph in BitmapFont.glyphs) {
                // each place in the cell, against the background at the same place
                for (place in 0 until glyph.w * glyph.h) {
                    val x = glyph.x + place % glyph.w
                    val y = glyph.y + place / glyph.w
                    if (x >= width || y >= height) continue
                    val far = distance(pixels[y * width + x], paper[place])
                    ink[y * width + x] = far
                    if (far > strongest) strongest = far
                }
            }
            if (strongest == 0) return null
            for (i in ink.indices) ink[i] = ink[i] * OPAQUE / strongest
            return GlyphMask(ink, width, height)
        }

        /**
         * A glyph cell's background: the space glyph's own pixels, or, on a sheet too narrow
         * to hold it, the sheet's most common color in every place.
         */
        private fun paperOf(
            pixels: IntArray,
            width: Int,
            height: Int,
        ): IntArray {
            val space = BitmapFont.sprite(' ')
            if (space.x + space.w <= width && space.y + space.h <= height) {
                return IntArray(space.w * space.h) { i -> pixels[(space.y + i / space.w) * width + space.x + i % space.w] }
            }
            val commonest =
                pixels
                    .asList()
                    .subList(0, width * height)
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }!!
                    .key
            return IntArray(space.w * space.h) { commonest }
        }

        /** How far apart two colors are: their largest difference in one channel. */
        private fun distance(
            a: Int,
            b: Int,
        ): Int {
            val red = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
            val green = abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
            val blue = abs((a and 0xFF) - (b and 0xFF))
            return maxOf(red, green, blue)
        }
    }
}
