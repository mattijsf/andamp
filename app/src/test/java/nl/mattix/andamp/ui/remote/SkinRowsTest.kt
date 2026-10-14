// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.GlyphMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The list on a lent surface in the skin's font: its letters' pixels are the window's, whole blocks of the canvas. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinRowsTest {
    /** A car's screen at four to one, where a row is 19 of the window's pixels. */
    private val layout = RemoteListLayout(1280, 720, scale = SCALE, touchPx = 76)

    /** A text sheet whose one letter is an a with one pixel of ink, two down and one along. */
    private val glyphs: GlyphMask =
        checkNotNull(
            GlyphMask.of(
                IntArray(SHEET_W * SHEET_H) { PAPER }.also {
                    val cell = BitmapFont.sprite('a')
                    it[(cell.y + 2) * SHEET_W + cell.x + 1] = INK
                },
                SHEET_W,
                SHEET_H,
            ),
        )

    /** The canvas of a window [layout.width] wide, [SCALE] to one, with a row drawn at the top of the list. */
    private fun drawn(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(layout.width * SCALE, layout.height * SCALE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(SCALE.toFloat(), SCALE.toFloat()) }
        SkinRows(glyphs, layout, SCALE).draw(canvas, title, "", RED, layout.listTop.toFloat())
        return bitmap
    }

    @Test
    fun `a pixel of the font is a pixel of the window, a square block of the canvas`() {
        val g = SCALE
        val picture = drawn("a")
        // two of the font's pixels in from the left, and the letter's one pixel along
        val left = layout.playRight * SCALE + 3 * g
        // the letter is in the middle of the row, on a whole pixel of the canvas
        val top = Math.round((layout.listTop + (layout.rowH - 5) / 2f) * SCALE) + 2 * g

        for (y in top until top + g) {
            for (x in left until left + g) assertEquals("($x, $y)", RED, picture.getPixel(x, y))
        }
        assertFalse("left of it", picture.getPixel(left - 1, top) == RED)
        assertFalse("right of it", picture.getPixel(left + g, top) == RED)
        assertFalse("above it", picture.getPixel(left, top - 1) == RED)
        assertFalse("below it", picture.getPixel(left, top + g) == RED)
    }

    @Test
    fun `a title in a script the font has no letters for is left to the regular font`() {
        val rows = SkinRows(glyphs, layout, SCALE)

        assertTrue(rows.spell("1. Morning Drive"))
        assertFalse(rows.spell("1. 晨间驾驶"))
    }

    private companion object {
        const val SCALE = 4
        const val SHEET_W = 155
        const val SHEET_H = 18
        const val PAPER = 0xFF101820.toInt()
        const val INK = 0xFF10F820.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
