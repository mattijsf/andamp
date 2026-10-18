// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.packapi.PackApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A square frame with a dot in its middle, drawn on a grid of five by five cells, as a pack's
 * monochrome icon layer or the mark it names draws one; the grid is [share] of the drawable's width.
 */
internal class Frame(
    private val share: Float = 0.5f,
) : Drawable() {
    private val paint = Paint().apply { color = Color.WHITE }

    override fun draw(canvas: Canvas) {
        val u = bounds.width() * share / CELLS
        val left = bounds.exactCenterX() - u * CELLS / 2
        val top = bounds.exactCenterY() - u * CELLS / 2

        fun cell(
            x: Int,
            y: Int,
            w: Int = 1,
            h: Int = 1,
        ) = canvas.drawRect(left + x * u, top + y * u, left + (x + w) * u, top + (y + h) * u, paint)
        cell(0, 0, w = CELLS)
        cell(0, CELLS - 1, w = CELLS)
        cell(0, 1, h = CELLS - 2)
        cell(CELLS - 1, 1, h = CELLS - 2)
        cell(2, 2)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val CELLS = 5
    }
}

/** A pack's mark, from its meta-data or its launcher icon, and the letter where neither tells it apart. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PackMarksTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val frameArt = listOf("#####", "#...#", "#.#.#", "#...#", "#####")

    /** A pack's resources holding its drawable [MARK] and its pixel art [PIXELS]. */
    @Suppress("DEPRECATION") // the constructor a pack's resources stand in with
    private val packResources =
        object : Resources(app.resources.assets, app.resources.displayMetrics, app.resources.configuration) {
            override fun getDrawable(
                id: Int,
                theme: Theme?,
            ): Drawable = if (id == MARK) Frame(share = 0.8f) else throw NotFoundException()

            override fun getStringArray(id: Int): Array<String> =
                if (id == PIXELS) arrayOf("#.#", ".#.", "#.#") else throw NotFoundException()
        }

    private fun icon(
        foreground: Drawable?,
        monochrome: Drawable?,
    ) = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), foreground, monochrome)

    @Test
    fun `a pack that names its mark gets its drawing and its pixel art`() {
        val meta =
            Bundle().apply {
                putInt(PackApi.META_MARK, MARK)
                putInt(PackApi.META_MARK_PIXELS, PIXELS)
            }

        val mark = PackMarks.markFrom(meta, packResources, icon(null, null), "Example")

        assertEquals(listOf("#.#", ".#.", "#.#"), mark.pixels)
        assertNotNull(mark.mask)
        assertEquals("cropped to its ink, so it is square", mark.mask!!.width, mark.mask!!.height)
        assertEquals("E", mark.letter)
    }

    @Test
    fun `an older pack is marked from its icon's monochrome layer, in pixel art made from it`() {
        val mark = PackMarks.markFrom(null, null, icon(ColorDrawable(Color.RED), Frame()), "Example")

        assertNotNull(mark.mask)
        assertEquals(frameArt, mark.pixels)
    }

    @Test
    fun `without a monochrome layer the foreground's shape is taken`() {
        assertEquals(frameArt, PackMarks.markFrom(null, null, icon(Frame(), null), "Example").pixels)
    }

    @Test
    fun `an icon whose layer fills its square, or a flat one, leaves the letter`() {
        val full = PackMarks.markFrom(null, null, icon(ColorDrawable(Color.RED), null), "Example")
        assertNull(full.mask)
        assertEquals(emptyList<String>(), full.pixels)

        assertNull(PackMarks.markFrom(null, null, ColorDrawable(Color.RED), "Example").mask)
    }

    @Test
    fun `pixel art made from a picture keeps its shape, and a blob or a speck makes none`() {
        val bar = PackMarks.pixelsFrom(70, 10) { _, _ -> 255 }
        assertEquals("a filled picture says nothing", emptyList<String>(), bar)

        val stripes = PackMarks.pixelsFrom(70, 10) { x, _ -> if ((x / 10) % 2 == 0) 255 else 0 }
        assertEquals("a wide picture keeps its shape in one row", listOf("#.#.#.#"), stripes)

        assertEquals(emptyList<String>(), PackMarks.pixelsFrom(50, 50) { x, y -> if (x < 10 && y < 10) 255 else 0 })
    }

    private companion object {
        const val MARK = 0x7f0f0001
        const val PIXELS = 0x7f0f0002
    }
}
