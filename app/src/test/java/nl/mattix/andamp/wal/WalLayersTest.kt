// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.skin.BitmapDef
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.LiveRun
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.Rgb
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.WindowPlan
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * What a canvas keeps of a skin between frames ([WalLayers]): what it records again for each kind
 * of change, what it lets go, when it draws the plain way, and that its frames have exactly the
 * pixels [drawWalStack] gives, at a whole scale and in the layer of a fractional one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalLayersTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val graphics = GraphicsContext(FrameLayout(app))
    private val records = ArrayList<String>()

    @After
    fun switchesBack() {
        WalRender.layers = true
        WalRender.direct = false
        WalRender.wholeOffscreen = true
        WalRender.partial = true
    }

    private fun layers(clearBelow: Boolean = true) =
        WalLayers(graphics::createGraphicsLayer, graphics::releaseGraphicsLayer, clearBelow, nodes = false, keeps = true).also {
            it.onRecord = { kind, id -> records += "$kind $id" }
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

    private val host by lazy {
        val sheet = png(40, 30) { x, y -> (0xFF shl 24) or (x * 6 shl 16) or (y * 8 shl 8) or 0x30 }
        val shape =
            png(40, 30) { x, y ->
                val d = kotlin.math.hypot(x - 20.0, y - 15.0)
                val a = (255 * (16.0 - d).coerceIn(0.0, 1.0) * if (d < 13) 1.0 else 0.6).toInt()
                (a shl 24) or 0x102030
            }
        val m = mapOf("sheet.png" to sheet, "shape.png" to shape)
        WalHost(
            object : SkinFiles {
                override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

                override fun names(): List<String> = m.keys.toList()
            },
            app.cacheDir,
        )
    }

    private val region =
        Region.Op(
            Region.Pic(DrawCmd.Image(BitmapDef("shape", "shape.png", 0, 0, 40, 30), Rect(0, 0, 40, 30), 255)),
            emptyList(),
            emptyList(),
            listOf(Region.Box(Rect(18, 13, 4, 4))),
        )

    /** What the stack shows: a label color, a visualizer's pixels, and whether the second window is open. */
    private var label = 0xC0FFEE
    private var vis = 1
    private var second = true
    private var secondRect = Rect(0, 30, 40, 10)

    /** The shaped main window: its live run is the visualizer, command 3. */
    private fun main(): List<DrawCmd> =
        listOf(
            DrawCmd.WindowStart(region),
            DrawCmd.Image(BitmapDef("bg", "sheet.png", 0, 0, 40, 30), Rect(0, 0, 40, 30), 255),
            DrawCmd.Fill(Rect(4, 4, 10, 6), Rgb(label), 128),
            DrawCmd.Pixels(Rect(20, 4, 8, 4), 2, 2, intArrayOf(0xFF000000.toInt() or vis, -1, 0x80FF0000.toInt(), vis), 255),
            DrawCmd.Image(BitmapDef("part", "sheet.png", 10, 10, 7, 5), Rect(20, 12, 14, 15), 200),
            DrawCmd.WindowEnd,
        )

    /** The second window: a picture from its top, or above it, toward its bottom, when its height is negative. */
    private fun under(): List<DrawCmd> =
        listOf(DrawCmd.Image(BitmapDef("bg2", "sheet.png", 5, 5, 20, 10), Rect(0, minOf(0, secondRect.height), 40, 10), 255))

    private fun stack(): List<Pair<Rect, List<DrawCmd>>> =
        listOf(Rect(0, 0, 40, 30) to main()) + if (second) listOf(secondRect to under()) else emptyList()

    /** Plans for [stack]: the main window at [mainVersion], its run at [runVersion] unless [run] is off. */
    private fun plans(
        mainVersion: Long,
        runVersion: Long,
        run: Boolean = true,
    ): List<WindowPlan> =
        listOf(WindowPlan(1, mainVersion, if (run) listOf(LiveRun(11, runVersion, 3, 4, Rect(20, 4, 8, 4))) else emptyList())) +
            if (second) listOf(WindowPlan(2, 1, emptyList())) else emptyList()

    private fun bytes(
        w: Int,
        h: Int,
        draw: DrawScope.() -> Unit,
    ): ByteArray {
        val img = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat()), draw)
        val b = img.asAndroidBitmap()
        return ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()
    }

    /**
     * Draws a frame with [layers] at [scale] and checks it against [drawWalStack]'s: at a whole
     * scale the frame itself; at a fractional one what its stack layer holds, drawn at the whole
     * scale above (the shrinking after is the same for both).
     */
    private fun frame(
        layers: WalLayers,
        plans: List<WindowPlan>?,
        scale: Float = 2f,
    ): List<String> {
        records.clear()
        val s = stack()
        val whole = kotlin.math.ceil(scale).toInt()
        val w = s.maxOf { it.first.right } * whole
        val h = s.maxOf { it.first.bottom } * whole
        val ours: ByteArray
        val plain: ByteArray
        if (whole.toFloat() == scale) {
            ours = bytes(w, h) { drawWalStackLayered(s, plans, host, scale, layers) }
            plain = bytes(w, h) { drawWalStack(s, host, scale, 0f, layer = null) }
        } else {
            ours =
                bytes(w, h) {
                    if (plans == null || !layers.suits(s, plans, whole, fractional = true)) {
                        drawStackLayer(s, host, whole)
                    } else {
                        host.drawing.begin(null)
                        layers.draw(this, s, plans, host, 1f, whole, fractional = true)
                        host.drawing.end()
                    }
                }
            plain = bytes(w, h) { drawStackLayer(s, host, whole) }
        }
        assertArrayEquals("the same pixels as drawn the plain way", plain, ours)
        return records.toList()
    }

    @Test
    fun `the first frame records every part, and an unchanged frame records nothing again`() {
        val layers = layers()
        assertEquals(listOf("run 11", "window 1", "window 2", "stack 0"), frame(layers, plans(1, 1)))
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
    }

    @Test
    fun `a change in a run records that run alone`() {
        val layers = layers()
        frame(layers, plans(1, 1))
        vis = 0x40
        assertEquals(listOf("run 11"), frame(layers, plans(1, 2)))
        vis = 0x80
        assertEquals(listOf("run 11"), frame(layers, plans(1, 3)))
    }

    @Test
    fun `a change outside the runs records its window and no run that did not change`() {
        val layers = layers()
        frame(layers, plans(1, 1))
        label = 0x123456
        assertEquals(listOf("window 1"), frame(layers, plans(2, 1)))
    }

    @Test
    fun `a window that opens is recorded with the stack, and one that closes is let go`() {
        val layers = layers()
        second = false
        frame(layers, plans(1, 1))
        second = true
        assertEquals(listOf("window 2", "stack 0"), frame(layers, plans(1, 1)))
        assertEquals(2, layers.keptWindows)
        second = false
        assertEquals(listOf("stack 0"), frame(layers, plans(1, 1)))
        assertEquals(1, layers.keptWindows)
    }

    @Test
    fun `a run that ends is let go and its window recorded again`() {
        val layers = layers()
        frame(layers, plans(1, 1))
        assertEquals(1, layers.keptRuns)
        assertEquals(listOf("window 1"), frame(layers, plans(2, 1, run = false)))
        assertEquals(0, layers.keptRuns)
    }

    @Test
    fun `a frame without plans, or with the switch off, is drawn the plain way and keeps nothing`() {
        val layers = layers()
        frame(layers, plans(1, 1))
        assertEquals(emptyList<String>(), frame(layers, null))
        assertEquals(0, layers.keptWindows)
        assertEquals(listOf("run 11", "window 1", "window 2", "stack 0"), frame(layers, plans(1, 1)))

        WalRender.layers = false
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
        assertEquals(0, layers.keptWindows)
        WalRender.layers = true
        assertEquals(listOf("run 11", "window 1", "window 2", "stack 0"), frame(layers, plans(1, 1)))
    }

    @Test
    fun `a window that moves and draws the same is moved, not recorded again`() {
        val layers = layers()
        secondRect = Rect(0, 30, 30, 10)
        frame(layers, plans(1, 1))
        secondRect = Rect(6, 30, 30, 10)
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
        secondRect = Rect(10, 30, 30, 10)
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
        frame(layers, plans(1, 1), scale = 1.5f)
        secondRect = Rect(4, 30, 30, 10)
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1), scale = 1.5f))
    }

    @Test
    fun `a window without a positive height is drawn the plain way, where its clip reaches`() {
        val layers = layers()
        frame(layers, plans(1, 1))
        // the canvas clips between the window's bottom and its top, over the main window
        secondRect = Rect(0, 30, 40, -10)
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
        assertEquals(0, layers.keptWindows)
        secondRect = Rect(0, 30, 40, 0)
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
    }

    @Test
    fun `with whole scales switched off, a whole scale is drawn the plain way and a fractional one keeps its parts`() {
        val layers = layers()
        WalRender.wholeOffscreen = false
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
        assertEquals(0, layers.keptWindows)
        assertEquals(listOf("run 11", "window 1", "window 2", "stack 0"), frame(layers, plans(1, 1), scale = 1.5f))
    }

    @Test
    fun `with partial drawing switched off, the stack layer is recorded again every frame`() {
        val layers = layers()
        WalRender.partial = false
        assertEquals(listOf("run 11", "window 1", "window 2", "stack 0"), frame(layers, plans(1, 1)))
        assertEquals(listOf("stack 0"), frame(layers, plans(1, 1)))
        vis = 0x40
        assertEquals(listOf("run 11", "stack 0"), frame(layers, plans(1, 2)))
        WalRender.partial = true
        assertEquals(emptyList<String>(), frame(layers, plans(1, 2)))
    }

    @Test
    fun `after a draw that failed everything is recorded again`() {
        val layers = layers()
        frame(layers, plans(1, 1))
        layers.drop()
        assertEquals(listOf("run 11", "window 1", "window 2", "stack 0"), frame(layers, plans(1, 1)))
    }

    @Test
    fun `a whole scale with something drawn under the stack is drawn the plain way`() {
        val layers = layers(clearBelow = false)
        assertEquals(emptyList<String>(), frame(layers, plans(1, 1)))
        assertEquals(0, layers.keptWindows)
    }

    @Test
    fun `a fractional scale keeps its parts in the stack layer, with the first window masked in place`() {
        val layers = layers(clearBelow = false)
        assertEquals(listOf("run 11", "window 1", "window 2", "stack 0"), frame(layers, plans(1, 1), scale = 1.5f))
        vis = 0x33
        assertEquals(listOf("run 11"), frame(layers, plans(1, 2), scale = 1.5f))
        label = 0x654321
        assertEquals(listOf("window 1"), frame(layers, plans(2, 2), scale = 1.5f))
    }

    @Test
    fun `the first window drawn at another scale kind is recorded again`() {
        val layers = layers()
        frame(layers, plans(1, 1), scale = 1.5f)
        // at a whole scale the first window draws its shape in a separate layer, as drawWalStack does
        assertEquals(listOf("window 1", "stack 0"), frame(layers, plans(1, 1), scale = 3f))
    }
}
