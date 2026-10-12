// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlyphMaskTest {
    private val paper = 0xFF101820.toInt()
    private val inkColor = 0xFF10F820.toInt()
    private val red = 0xFF0000

    /** A full-size sheet of [paper], with [paint] run over it. */
    private fun sheet(
        width: Int = SHEET_W,
        height: Int = SHEET_H,
        paint: (IntArray) -> Unit = {},
    ): IntArray = IntArray(width * height) { paper }.also(paint)

    /** Sets the pixel at ([gx], [gy]) of the glyph for [c]. */
    private fun IntArray.dot(
        c: Char,
        gx: Int,
        gy: Int,
        color: Int,
        width: Int = SHEET_W,
    ) {
        val cell = BitmapFont.sprite(c)
        this[(cell.y + gy) * width + cell.x + gx] = color
    }

    private fun written(
        mask: GlyphMask,
        text: String,
        x: Int = 0,
        y: Int = 0,
        stride: Int = 12,
        rows: Int = BitmapFont.CHAR_H,
        clipRight: Int = stride,
    ): IntArray = IntArray(stride * rows).also { mask.write(text, x, y, red, it, stride, clipRight) }

    @Test
    fun `a letter is written in the color asked for, wherever the skin painted it`() {
        val mask = GlyphMask.of(sheet { it.dot('a', 1, 2, inkColor) }, SHEET_W, SHEET_H)!!

        val out = written(mask, "a")

        val expected = IntArray(12 * BitmapFont.CHAR_H).also { it[2 * 12 + 1] = 0xFFFF0000.toInt() }
        assertArrayEquals(expected, out)
    }

    @Test
    fun `each letter is one cell further along, and a space leaves its cell alone`() {
        val mask = GlyphMask.of(sheet { it.dot('a', 0, 0, inkColor) }, SHEET_W, SHEET_H)!!

        val out = written(mask, "a a", x = 1, y = 1, stride = 16, rows = 8)

        assertEquals(0xFFFF0000.toInt(), out[1 * 16 + 1])
        assertEquals(0xFFFF0000.toInt(), out[1 * 16 + 11])
        assertEquals(2, out.count { it != 0 })
    }

    @Test
    fun `a paler pixel of a letter is written thinner`() {
        // half way from the paper to the ink, in the one channel that differs
        val half = 0xFF108820.toInt()
        val mask =
            GlyphMask.of(
                sheet {
                    it.dot('a', 0, 0, inkColor)
                    it.dot('a', 1, 0, half)
                },
                SHEET_W,
                SHEET_H,
            )!!

        val out = written(mask, "a")

        assertEquals(0xFF, out[0] ushr 24)
        assertEquals(0x70 * 255 / 0xE0, out[1] ushr 24)
        assertEquals(red, out[1] and 0xFFFFFF)
    }

    @Test
    fun `a cell shaded from top to bottom is still empty where the space is`() {
        val shaded =
            sheet { pixels ->
                for (y in 0 until SHEET_H) {
                    for (x in 0 until SHEET_W) pixels[y * SHEET_W + x] = 0xFF000000.toInt() or (y % BitmapFont.CHAR_H * 0x101010)
                }
                pixels.dot('a', 2, 3, 0xFFFFFFFF.toInt())
            }
        val mask = GlyphMask.of(shaded, SHEET_W, SHEET_H)!!

        val out = written(mask, "ab")

        assertEquals(1, out.count { it != 0 })
        assertEquals(0xFF, out[3 * 12 + 2] ushr 24)
    }

    @Test
    fun `nothing is written past the clip or outside the block`() {
        val full =
            sheet { pixels ->
                for (gy in 0 until BitmapFont.CHAR_H) for (gx in 0 until BitmapFont.CHAR_W) pixels.dot('a', gx, gy, inkColor)
            }
        val mask = GlyphMask.of(full, SHEET_W, SHEET_H)!!

        val clipped = written(mask, "aaa", clipRight = 7)
        assertEquals(7 * BitmapFont.CHAR_H, clipped.count { it != 0 })

        // two rows above the block and one column left of it: what is inside is written
        val shifted = written(mask, "a", x = -1, y = -2)
        assertEquals((BitmapFont.CHAR_W - 1) * (BitmapFont.CHAR_H - 2), shifted.count { it != 0 })

        // a block shorter than a glyph
        val short = written(mask, "a", rows = 3)
        assertEquals(BitmapFont.CHAR_W * 3, short.count { it != 0 })
    }

    @Test
    fun `a sheet too narrow for the space glyph takes its commonest color for the background`() {
        val width = 50
        val narrow = sheet(width = width) { it.dot('a', 0, 0, inkColor, width) }
        val mask = GlyphMask.of(narrow, width, SHEET_H)!!

        // 'z' is beyond the sheet's edge and writes nothing
        val out = written(mask, "az")

        assertEquals(1, out.count { it != 0 })
        assertEquals(0xFFFF0000.toInt(), out[0])
    }

    @Test
    fun `a sheet with one color all over has no letters`() {
        assertNull(GlyphMask.of(sheet(), SHEET_W, SHEET_H))
        assertNull(GlyphMask.of(IntArray(0), 0, 0))
    }

    @Test
    fun `paint outside the glyphs does not make the letters fainter`() {
        // the two cells of the top row the font does not use
        val signed =
            sheet {
                it.dot('a', 0, 0, 0xFF103820.toInt())
                it[0 * SHEET_W + 28 * BitmapFont.CHAR_W] = 0xFFFFFFFF.toInt()
            }
        val mask = GlyphMask.of(signed, SHEET_W, SHEET_H)!!

        assertEquals(0xFF, written(mask, "a")[0] ushr 24)
    }

    private companion object {
        const val SHEET_W = 155
        const val SHEET_H = 18
    }
}
