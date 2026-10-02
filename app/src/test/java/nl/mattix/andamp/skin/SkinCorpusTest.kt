// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.app.Application
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.ui.window.GenFrame
import nl.mattix.andamp.ui.window.GenTitleBar
import nl.mattix.andamp.ui.window.PleditWindowFrame
import nl.mattix.andamp.ui.window.WindowFrame
import nl.mattix.andamp.ui.window.frameFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders a floating window's chrome with every skin in a local corpus.
 *
 * Skins are other people's art and are not kept in this repository, so the
 * corpus is opt-in: point `ANDAMP_SKIN_CORPUS` at a directory of .wsz files (or
 * put them in `skin-corpus/` at the repository root) and this runs; otherwise
 * it skips.
 *
 * The assertions do not depend on which skin is drawn:
 * - a skin either owns usable generic art or wears playlist art; both draw
 * - the title bar is painted edge to edge, with no transparent seam
 * - the title lands inside its plate rather than over the frame's rails
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinCorpusTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun corpus(): List<File> {
        val dir =
            System.getenv("ANDAMP_SKIN_CORPUS")?.let(::File)
                ?: File(System.getProperty("user.dir") ?: ".", "../skin-corpus")
        if (!dir.isDirectory) return emptyList()
        return dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".wsz", ignoreCase = true) }
            .sorted()
    }

    private fun render(
        width: Int,
        height: Int,
        block: DrawScope.() -> Unit,
    ) = ImageBitmap(width, height)
        .also { image ->
            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                Canvas(image),
                Size(width.toFloat(), height.toFloat()),
                block,
            )
        }.asAndroidBitmap()

    private fun frameOf(
        skin: Skin,
        frame: WindowFrame,
        title: String,
    ) = render(WIDTH, HEIGHT) {
        with(frame) { draw(skin, WIDTH, HEIGHT, title, closePressed = false) }
    }

    /** Any transparent pixel in the title bar means a piece was missing or mis-sized. */
    private fun holeInTitleBar(
        bitmap: android.graphics.Bitmap,
        titleH: Int,
    ): String? {
        for (x in 0 until WIDTH) {
            for (y in 0 until titleH) {
                if (bitmap.getPixel(x, y) ushr ALPHA_SHIFT == 0) return "hole in the title bar at ($x, $y)"
            }
        }
        return null
    }

    /**
     * Where the title changed pixels, compared with the same frame drawn
     * without one: inside its plate is required, left of it is a spill onto the
     * frame's own rails.
     */
    private fun titleSpread(
        withTitle: android.graphics.Bitmap,
        blank: android.graphics.Bitmap,
        titleH: Int,
        plateX: Int,
    ): Pair<Boolean, Int> {
        var inPlate = false
        var outside = 0
        for (x in 0 until WIDTH) {
            for (y in 0 until titleH) {
                if (withTitle.getPixel(x, y) == blank.getPixel(x, y)) continue
                if (x < plateX) outside++ else inPlate = true
            }
        }
        return inPlate to outside
    }

    @Test
    fun `every skin in the corpus draws a complete floating window`() {
        val skins = corpus()
        assumeTrue("no skin corpus on this machine", skins.isNotEmpty())
        val base = SkinLoader.loadBase(app)
        val failures = mutableListOf<String>()

        skins.forEach { file ->
            val skin = file.inputStream().use { SkinLoader.load(it, fallback = base, name = file.name) }
            val frame = frameFor(skin)
            val expected = if (skin.ownsGenArt) GenFrame else PleditWindowFrame
            if (frame !== expected) failures += "${file.name}: wrong frame for ownsGenArt=${skin.ownsGenArt}"

            val bitmap = frameOf(skin, frame, TITLE)
            holeInTitleBar(bitmap, frame.titleH)?.let { failures += "${file.name}: $it" }

            // only the generic frame has rails to spill onto; the playlist-art
            // frame centers its title over plain filler
            val plateX = if (skin.ownsGenArt) GenTitleBar.titleX(WIDTH, 0) else 0
            val (inPlate, outside) = titleSpread(bitmap, frameOf(skin, frame, ""), frame.titleH, plateX)
            if (!inPlate) failures += "${file.name}: the title drew nothing"
            if (outside > 0) failures += "${file.name}: the title spilled $outside px left of its plate"
        }

        assertTrue("all ${skins.size} skins draw a complete window:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `a skin either owns generic art or borrows the fallback's title font`() {
        val skins = corpus()
        assumeTrue("no skin corpus on this machine", skins.isNotEmpty())
        val base = SkinLoader.loadBase(app)

        skins.forEach { file ->
            val skin = file.inputStream().use { SkinLoader.load(it, fallback = base, name = file.name) }
            // a window with no font at all would render an empty title bar
            assertEquals("${file.name} has a usable title font", true, skin.genTitleFont != null)
        }
    }

    private companion object {
        const val WIDTH = 275
        const val HEIGHT = 154
        const val TITLE = "SKINS"
        const val ALPHA_SHIFT = 24
    }
}
