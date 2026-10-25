// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
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
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.DrawPlanner
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * The corpus skins (local files in the modernskin module's test resources, skipped without them)
 * playing, drawn frame after frame with kept parts ([WalLayers]) as the engine plans them, each
 * frame drawn checked pixel for pixel against [drawWalStack]'s drawing of the same frame: at a
 * whole scale, and the stack layer of a fractional one. The screen skips frames as it does when
 * the engine runs ahead; a theme switch and a stop come along the way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalLayersCorpusTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val graphics = GraphicsContext(FrameLayout(app))
    private val res = File("../modernskin/src/test/resources")

    @Test
    fun `corpus skins draw the same pixels with kept parts, at a whole scale`() = SKINS.forEach { play(it, fractional = false) }

    @Test
    fun `corpus skins draw the same pixels with kept parts, in a fractional scale's layer`() =
        SKINS.forEach { play(it, fractional = true) }

    private fun play(
        name: String,
        fractional: Boolean,
    ) {
        val file = File(res, name)
        assumeTrue("$name is a local skin file, not in the repository", file.exists())
        val siblings =
            res.listFiles { f -> f.name.endsWith(".wal") }.orEmpty().associate { it.nameWithoutExtension to it } +
                mapOf("Big Bento" to File(res, "BigBento.wal"))
        val files = WalFiles.open(file, siblings)
        var t = START
        val host = WalHost(files, app.cacheDir)
        val player =
            FakePlayer().apply {
                clock = { t }
                lengthMs = 600_000
                title = "Andamp - a title long enough to scroll in every ticker"
            }
        val rt = SkinRuntime(files, host, player).apply { clock = { t } }
        rt.load()
        if (name.contains(
                "Bento",
            )
        ) {
            rt.mainLayout?.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(633), Value.Int(1258)))
        }
        rt.viewSize = 633 to 1258
        val planner = DrawPlanner()
        val layers =
            WalLayers(
                graphics::createGraphicsLayer,
                graphics::releaseGraphicsLayer,
                clearBelow = true,
                nodes = false,
                keeps = true,
            )
        val random = Random(name.hashCode())
        val themes = rt.gammaSetNames()
        var compared = 0
        var records = 0
        layers.onRecord = { _, _ -> records++ }
        repeat(TICKS) { tick ->
            t += FRAME
            when (tick) {
                PLAY_AT -> player.play()
                THEME_AT -> themes.getOrNull(1)?.let(rt::selectGammaSet)
                STOP_AT -> player.stop()
            }
            if (!rt.tick() && tick != 0) return@repeat
            val planned = planner.draw(rt, t)
            // the screen draws some frames and skips others
            if (random.nextInt(3) == 0 && tick != TICKS - 1) return@repeat
            val stack = planned.stack
            val w = stack.maxOf { it.first.right } * SCALE
            val h = stack.maxOf { it.first.bottom } * SCALE
            val ours: ByteArray
            val plain: ByteArray
            if (fractional) {
                ours =
                    bytes(w, h) {
                        assertTrue(layers.suits(stack, planned.plans, SCALE, fractional = true))
                        host.drawing.begin(rt.currentGammaSet)
                        layers.draw(this, stack, planned.plans, host, 1f, SCALE, fractional = true)
                        host.drawing.end()
                    }
                plain = bytes(w, h) { drawStackLayer(stack, host, SCALE) }
            } else {
                ours =
                    bytes(w, h) { drawWalStackLayered(stack, planned.plans, host, SCALE.toFloat(), layers, rt.currentGammaSet) }
                plain =
                    bytes(w, h) { drawWalStack(stack, host, SCALE.toFloat(), 0f, layer = null, themeKey = rt.currentGammaSet) }
            }
            assertArrayEquals("$name tick $tick", plain, ours)
            compared++
        }
        println("LAYERS $name fractional=$fractional: $compared frames compared, $records recordings")
        assertTrue(compared > 0)
        layers.release()
    }

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

    private companion object {
        val SKINS =
            listOf(
                "WinampModern566.wal",
                "Bento.wal",
                "BigBento.wal",
                "CornerAmp_Redux.wal",
                "MMD3.wal",
                "DeClassified.wal",
                "Winamp3x.wal",
                "Winamp2000SP4.wal",
            )
        const val START = 1_792_324_800_000L
        const val FRAME = 16L
        const val TICKS = 150
        const val PLAY_AT = 10
        const val THEME_AT = 80
        const val STOP_AT = 120
        const val SCALE = 2
    }
}
