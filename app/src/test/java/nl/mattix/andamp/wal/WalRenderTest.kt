// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.skin.BitmapDef
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.Rgb
import nl.mattix.andamp.modernskin.skin.SkinFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs

/** How the renderer draws a shaped window at a fractional scale, and the direct drawing behind the adb switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalRenderTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun layerAgain() {
        WalRender.direct = false
    }

    private fun png(
        w: Int,
        h: Int,
        color: (Int, Int) -> Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bitmap.setPixel(x, y, color(x, y))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun files(vararg entries: Pair<String, ByteArray>): SkinFiles =
        object : SkinFiles {
            private val m = entries.toMap()

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    /** A canvas that counts the offscreen layers drawn on it. */
    private class CountingCanvas(
        private val inner: Canvas,
    ) : Canvas by inner {
        var layers = 0

        override fun saveLayer(
            bounds: androidx.compose.ui.geometry.Rect,
            paint: Paint,
        ) {
            layers++
            inner.saveLayer(bounds, paint)
        }
    }

    /** [stack] drawn by [draw] into a clear picture [w] x [h], with the layers it took. */
    private fun render(
        w: Int,
        h: Int,
        draw: DrawScope.() -> Unit,
    ): Pair<ImageBitmap, Int> {
        val img = ImageBitmap(w, h)
        val canvas = CountingCanvas(Canvas(img))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat()), draw)
        return img to canvas.layers
    }

    /** The picture's bytes as stored, premultiplied, so a faint pixel compares as faint. */
    private fun ImageBitmap.bytes(): ByteArray {
        val b = asAndroidBitmap()
        return ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()
    }

    /** A sheet of colors, a round window shape with soft edges and a hole, and a second window under it. */
    private fun shapedStack(host: (SkinFiles) -> WalHost): Pair<WalHost, List<Pair<Rect, List<DrawCmd>>>> {
        val sheet = png(40, 30) { x, y -> (0xFF shl 24) or (x * 6 shl 16) or (y * 8 shl 8) or 0x30 }
        val shape =
            png(40, 30) { x, y ->
                val d = kotlin.math.hypot(x - 20.0, y - 15.0)
                val a = (255 * (16.0 - d).coerceIn(0.0, 1.0) * if (d < 13) 1.0 else 0.6).toInt()
                (a shl 24) or 0x102030
            }
        val h = host(files("sheet.png" to sheet, "shape.png" to shape))
        val pic = DrawCmd.Image(BitmapDef("shape", "shape.png", 0, 0, 40, 30), Rect(0, 0, 40, 30), 255)
        val region = Region.Op(Region.Pic(pic), emptyList(), emptyList(), listOf(Region.Box(Rect(18, 13, 4, 4))))
        val main =
            listOf(
                DrawCmd.WindowStart(region),
                DrawCmd.Image(BitmapDef("bg", "sheet.png", 0, 0, 40, 30), Rect(0, 0, 40, 30), 255),
                DrawCmd.Fill(Rect(4, 4, 10, 6), Rgb(0xC0FFEE), 128),
                DrawCmd.Image(BitmapDef("part", "sheet.png", 10, 10, 7, 5), Rect(20, 6, 14, 15), 200),
                DrawCmd.WindowEnd,
            )
        val under = listOf(DrawCmd.Image(BitmapDef("bg2", "sheet.png", 5, 5, 20, 10), Rect(0, 0, 40, 10), 255))
        return h to listOf(Rect(0, 0, 40, 30) to main, Rect(0, 30, 40, 10) to under)
    }

    @Test
    fun `a shaped window at a fractional scale draws the same pixels with one offscreen layer fewer`() {
        val (host, stack) = shapedStack { WalHost(it, app.cacheDir) }
        // at the whole scale above the fractional one: each window in a layer for its shape, and
        // what the stack's layer records
        val (layered, layeredCount) = render(80, 80) { drawWindowsAt(stack, host, 2f) }
        val (cut, cutCount) = render(80, 80) { drawStackLayer(stack, host, 2) }

        assertEquals(1, layeredCount)
        assertEquals(0, cutCount)
        val a = layered.bytes()
        val b = cut.bytes()
        var worst = 0
        for (i in a.indices) worst = maxOf(worst, abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)))
        assertTrue("differs by $worst in a channel", worst <= 1)
        // the shape did cut: the hole and the corners are clear
        val px = layered.asAndroidBitmap()
        assertEquals(0, px.getPixel(40, 30) ushr 24)
        assertEquals(0, px.getPixel(1, 1) ushr 24)
        assertEquals(0xFF, px.getPixel(20, 30 - 4) ushr 24)
    }

    @Test
    fun `drawn directly, pieces meet on whole screen pixels and show nothing of what lies under them`() {
        // waits for the application's startup read of sharp pixels, which would set direct back
        WalSharp.read(app)
        WalRender.direct = true
        val gray = 0xFF808080.toInt()
        val magenta = 0xFFFF00FF.toInt()
        // a background with a magenta column that the piece over it hides, as Winamp3x's does
        val sheet = png(12, 4) { x, _ -> if (x == 6) magenta else gray }
        val host = WalHost(files("sheet.png" to sheet, "cover.png" to png(3, 3) { _, _ -> 0xFF000000.toInt() }), app.cacheDir)
        val cmds =
            listOf(
                DrawCmd.Image(BitmapDef("bg", "sheet.png", 0, 0, 9, 3), Rect(0, 0, 9, 3), 255),
                DrawCmd.Image(BitmapDef("cover", "cover.png", 0, 0, 3, 3), Rect(6, 0, 3, 3), 255),
                DrawCmd.Fill(Rect(0, 3, 9, 3), Rgb(0x00FF00), 255),
            )
        val stack = listOf(Rect(1, 0, 9, 6) to cmds)

        val (img, _) = render(20, 12) { drawWalStack(stack, host, 1.4f, 0f, layer = null) }

        val px = img.asAndroidBitmap()
        for (y in 0 until 12) {
            for (x in 0 until 20) {
                val c = px.getPixel(x, y)
                assertTrue("partial pixel at $x,$y", c ushr 24 == 0 || c ushr 24 == 0xFF)
                assertTrue("magenta at $x,$y", c != magenta)
            }
        }
        // the window runs from 1.4 to 14: the pixels whose centers lie inside
        assertEquals(0, px.getPixel(0, 1) ushr 24)
        for (x in 1 until 14) assertEquals("at $x", 0xFF, px.getPixel(x, 1) ushr 24)
        assertEquals(0, px.getPixel(14, 1) ushr 24)
    }
}
