// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.skin.BitmapDef
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.Gamma
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.SkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * What the phone keeps of a skin's pictures between frames: within a budget by bytes, never what
 * the frame on screen draws, and nothing of a color theme no longer worn; and visualizer pictures
 * filled into bitmaps it keeps rather than new ones each frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalHostTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A [w] x [h] PNG with a different opaque color in each pixel. */
    private fun png(
        w: Int,
        h: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bitmap.setPixel(x, y, (0xFF shl 24) or (x * 4 shl 16) or (y * 4 shl 8) or 0x40)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun files(vararg entries: Pair<String, ByteArray>): SkinFiles =
        object : SkinFiles {
            private val m = entries.toMap()

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private fun host(budget: Long = WalDrawing.BUDGET) =
        WalHost(files("sheet.png" to png(SHEET, SHEET)), app.cacheDir, drawingBudget = budget)

    /** [count] parts of the sheet, each 8 px square, drawn with [gamma]; [from] picks which parts. */
    private fun window(
        gamma: Gamma?,
        count: Int = 4,
        from: Int = 0,
    ): List<Pair<Rect, List<DrawCmd>>> {
        val cmds =
            (from until from + count).map { i ->
                val part = BitmapDef("p$i", "sheet.png", (i % 8) * 8, (i / 8) * 8, 8, 8, "g")
                DrawCmd.Image(part, Rect(i * 8, 0, 8, 8), 255).also { it.gamma = gamma }
            }
        return listOf(Rect(0, 0, 8 * (from + count), 8) to cmds)
    }

    /** One frame of [stack], drawn as the player draws it, in color theme [theme]. */
    private fun WalHost.frame(
        stack: List<Pair<Rect, List<DrawCmd>>>,
        theme: String? = null,
    ) {
        val img = ImageBitmap(stack.maxOf { it.first.right }, stack.maxOf { it.first.bottom })
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(img.width.toFloat(), img.height.toFloat())) {
            drawWalStack(stack, this@frame, 1f, 0f, layer = null, themeKey = theme)
        }
    }

    private fun gamma(i: Int) = Gamma(r = 100 * (i + 1), g = 0, b = -50 * i, boost = 0, gray = 0)

    @Test
    fun `twenty color themes keep the drawing caches within their budget`() {
        // a tinted sheet is 16 KB, each part 256 bytes: one theme's pictures take about 17 KB
        val budget = 40L * 1024
        val host = host(budget)
        host.frame(window(gamma(0)))
        val oneTheme = host.drawing.bytes

        // themes the screen cannot name: only the budget bounds what is kept
        for (i in 1 until 20) host.frame(window(gamma(i)))
        assertTrue("${host.drawing.bytes} kept", host.drawing.bytes <= budget + oneTheme)

        // themes by name: what the last one drew is all that is kept
        val named = host(budget)
        for (i in 0 until 20) named.frame(window(gamma(i)), theme = "t$i")
        assertEquals(oneTheme, named.drawing.bytes)
    }

    @Test
    fun `a new color theme lets go of the last theme's tinted pictures`() {
        val host = host()
        val untinted = window(null, count = 2, from = 4)
        host.frame(window(gamma(0)) + untinted, theme = "One")
        assertEquals(setOf(gamma(0)), host.drawing.tints())
        val made = host.drawing.made

        host.frame(window(gamma(1)) + untinted, theme = "Two")

        assertEquals(setOf(gamma(1)), host.drawing.tints())
        // the new theme's tinted sheet and its four parts, and nothing for the untinted parts
        assertEquals(made + 5, host.drawing.made)
    }

    @Test
    fun `what the current frame draws is never let go to make room`() {
        // a budget smaller than a single part
        val host = host(budget = 16)
        val stack = window(gamma(0), count = 16)
        host.frame(stack)
        assertTrue(host.drawing.bytes > 16)
        val made = host.drawing.made

        host.frame(stack)
        assertEquals("all kept for the next frame", made, host.drawing.made)

        // what the next frame no longer draws goes, down to the budget
        host.frame(window(null, count = 1))
        assertEquals(emptySet<Gamma>(), host.drawing.tints())
    }

    @Test
    fun `pictures the frame no longer draws stay within the budget, however much the frame draws`() {
        // room for four untinted parts (1 KB), not for a tinted sheet (16 KB)
        val host = host(budget = 2L * 1024)
        val untinted = window(null, count = 4, from = 8)
        host.frame(untinted)
        host.frame(window(gamma(0)))
        val made = host.drawing.made

        host.frame(untinted)

        assertEquals("the untinted parts were kept", made, host.drawing.made)
    }

    @Test
    fun `the sheet of a piece the frame draws stays, so a new piece of it is cut without tinting again`() {
        // less than the four untinted parts take
        val host = host(budget = 512)
        host.frame(window(gamma(0)) + window(null, count = 4, from = 8))
        host.frame(window(gamma(0)))
        val made = host.drawing.made

        host.frame(window(gamma(0), count = 5))

        assertEquals("the fifth part, cut from the tinted sheet kept", made + 1, host.drawing.made)
    }

    @Test
    fun `a picture kept under two names counts once`() {
        val sheet = SHEET * SHEET * 4L
        // room for one sheet: the frame after lets go of what does not fit
        val host = host(budget = sheet)
        val whole = BitmapDef("whole", "sheet.png", 0, 0, SHEET, SHEET, "g")
        val box = Rect(0, 0, SHEET, SHEET)
        // a window that draws the whole sheet tinted, shaped by the whole sheet as a mask: each
        // piece is its sheet, the tinted one and the cut mask
        val shape = Region.Op(Region.Box(box), emptyList(), listOf(Region.Pic(DrawCmd.Image(whole, box, 255))), emptyList())
        val image = DrawCmd.Image(whole, box, 255).also { it.gamma = gamma(0) }
        host.frame(listOf(box to listOf(DrawCmd.WindowStart(shape), image, DrawCmd.WindowEnd)))

        assertEquals("the tinted sheet and the cut mask", 2 * sheet, host.drawing.bytes)

        host.frame(window(null, count = 1))

        assertEquals("what is let go no longer counts, what is kept still does", host.drawing.keptBytes(), host.drawing.bytes)
        assertTrue(host.drawing.bytes <= sheet + 8 * 8 * 4)
    }

    @Test
    fun `window shapes take no room from the pictures`() {
        // room for one theme's pictures (17 KB), not for a single shape
        val budget = 20L * 1024
        val host = host(budget)
        val tinted = window(gamma(0))
        host.frame(tinted)
        for (i in 0 until 20) {
            // a large shape that moves, about 40 KB each frame, over another part
            val shape = Region.Box(Rect(0, 0, 100 + i, 100))
            val cmds = listOf(DrawCmd.WindowStart(shape)) + window(null, count = 1, from = 8).single().second + DrawCmd.WindowEnd
            host.frame(listOf(Rect(0, 0, 72, 8) to cmds))
        }
        assertTrue("${host.drawing.shapeBytes} in shapes", host.drawing.shapeBytes > budget)
        val made = host.drawing.made

        host.frame(tinted)

        assertEquals("the tinted sheet and its parts were kept", made, host.drawing.made)
    }

    @Test
    fun `drawing the same frame twice decodes, tints and cuts nothing new`() {
        val host = host()
        val stack = window(gamma(0), count = 8) + window(null, count = 8, from = 8)
        host.frame(stack, theme = "One")
        val decoded = host.decoded
        val made = host.drawing.made

        host.frame(stack, theme = "One")

        assertEquals(decoded, host.decoded)
        assertEquals(made, host.drawing.made)
    }

    @Test
    fun `a window shape that moves every frame keeps only the last few shapes`() {
        val host = host()
        for (i in 0 until 40) {
            // a drawer sliding out: the shape grows by a pixel each frame
            val shape = Region.Box(Rect(0, 0, 8 + i, 8))
            val cmds = listOf(DrawCmd.WindowStart(shape)) + window(null).single().second + DrawCmd.WindowEnd
            host.frame(listOf(Rect(0, 0, 48, 8) to cmds))
        }
        assertEquals(WalDrawing.MAX_SHAPES, host.drawing.shapesKept)
    }

    /** A visualizer's [w] x [h] picture in one color. */
    private fun vis(
        color: Int,
        w: Int = 4,
        h: Int = 2,
    ) = DrawCmd.Pixels(Rect(0, 0, w, h), w, h, IntArray(w * h) { color }, 255)

    private fun ImageBitmap.colors(): List<Int> {
        val b = asAndroidBitmap()
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }.toList()
    }

    @Test
    fun `a changing pixel picture reuses two bitmaps`() {
        val host = host()
        val seen = mutableListOf<ImageBitmap>()
        for (i in 0 until 6) {
            val color = (0xFF shl 24) or (i * 40)
            host.drawing.begin(null)
            val bitmap = host.pixels(vis(color))
            host.drawing.end()
            assertEquals(List(8) { color }, bitmap.colors())
            seen += bitmap
        }

        assertEquals(2, seen.map { System.identityHashCode(it) }.distinct().size)
        // never the bitmap the frame before used
        for (i in 1 until seen.size) assertNotSame(seen[i - 1], seen[i])
    }

    @Test
    fun `two visualizers of the same size in one frame keep separate pictures`() {
        val host = host()
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val all = mutableSetOf<Int>()
        repeat(3) {
            host.drawing.begin(null)
            val first = host.pixels(vis(red))
            val second = host.pixels(vis(blue))
            host.drawing.end()

            assertNotSame(first, second)
            assertEquals(List(8) { red }, first.colors())
            assertEquals(List(8) { blue }, second.colors())
            all += System.identityHashCode(first)
            all += System.identityHashCode(second)
        }
        assertEquals("two bitmaps for each", 4, all.size)
    }

    private companion object {
        const val SHEET = 64
    }
}
