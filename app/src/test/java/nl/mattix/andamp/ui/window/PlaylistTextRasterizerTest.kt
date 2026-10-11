// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlaylistTextRasterizerTest {
    private lateinit var rasterizer: PlaylistTextRasterizer
    private lateinit var textSheet: ImageBitmap
    private val layout = PlaylistLayout(116)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        rasterizer = PlaylistTextRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
        textSheet = SkinLoader.loadBase(context)[Sheet.TEXT]
    }

    /** How many pixels of [layer] are exactly a row color of the default style. */
    private fun rowColored(layer: ImageBitmap): Int {
        val pixels = layer.toPixelMap()
        var count = 0
        for (y in 0 until layer.height) {
            for (x in 0 until layer.width) {
                if (pixels[x, y] == PleditStyle.DEFAULT.normal || pixels[x, y] == PleditStyle.DEFAULT.current) count++
            }
        }
        return count
    }

    @Test
    fun `renders text pixels in the row colors at device scale`() {
        val state = WinampState()
        val scale = 3
        val layer = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, scale)
        assertTrue(layer.width == (layout.textRight - layout.textLeft) * scale)
        assertTrue(layer.height == layout.middleH * scale)
        // antialiased edges blend, but glyph cores keep the exact PLEDIT color
        assertTrue("over 100 text pixels are in PLEDIT colors: ${rowColored(layer)}", rowColored(layer) > 100)
    }

    @Test
    fun `layer is cached until an input changes`() {
        val state = WinampState()
        val first = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3)
        assertSame(first, rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3))

        state.playlistScroll = 2
        val scrolled = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3)
        assertNotSame(first, scrolled)

        state.selectedRows = setOf(1)
        assertNotSame(scrolled, rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3))

        assertNotSame(
            rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3),
            rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 4),
        )
    }

    /** Dragged wider and no taller, the window must not be handed the narrower window's text to stretch. */
    @Test
    fun `a window made wider gets a layer as wide as it is`() {
        val state = WinampState()
        val narrow = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3)

        val wider = PlaylistLayout(layout.height, layout.width + 50)
        val wide = rasterizer.textLayer(state, wider, PleditStyle.DEFAULT, 3)

        assertNotSame(narrow, wide)
        assertTrue(wide.width == (wider.textRight - wider.textLeft) * 3)
    }

    @Test
    fun `in the skin's font the layer is one pixel to the virtual pixel, in the row colors`() {
        val state = WinampState()
        val layer = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3, textSheet)

        assertEquals(layout.textRight - layout.textLeft, layer.width)
        assertEquals(layout.middleH, layer.height)
        assertTrue("over 100 text pixels are in PLEDIT colors: ${rowColored(layer)}", rowColored(layer) > 100)
        // the song that plays is in its own color
        val pixels = layer.toPixelMap()
        val currentRow = 3 + state.currentIndex * 13
        val current =
            (currentRow until currentRow + 13).sumOf { y ->
                (0 until layer.width).count { x ->
                    pixels[x, y] ==
                        PleditStyle.DEFAULT.current
                }
            }
        assertTrue("the current row is in the current color: $current", current > 20)
    }

    @Test
    fun `the skin's font is drawn the same at every scale`() {
        val state = WinampState()
        val small =
            rasterizer
                .textLayer(state, layout, PleditStyle.DEFAULT, 2, textSheet)
                .toPixelMap()
                .buffer
                .copyOf()
        val large = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 5, textSheet).toPixelMap().buffer

        assertTrue(small.contentEquals(large))
    }

    @Test
    fun `a title the skin's font cannot spell keeps the outline font, beside rows that are in the skin's`() {
        val state =
            WinampState().apply {
                playlist =
                    listOf(
                        Track("a", "Neon Cassette", "Midnight Drive", 254_000L),
                        Track("b", "Кино", "Группа крови", 285_000L),
                    )
                currentIndex = -1
            }
        val scale = 3
        val layer = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, scale, textSheet)

        // the outline font needs device resolution, so the whole layer is at it
        assertEquals((layout.textRight - layout.textLeft) * scale, layer.width)
        val pixels = layer.toPixelMap()

        fun inked(visRow: Int): Int {
            val top = (3 + visRow * 13) * scale
            return (top until top + 13 * scale).sumOf { y -> (0 until layer.width).count { x -> pixels[x, y].alpha > 0f } }
        }
        assertTrue("the first row is drawn: ${inked(0)}", inked(0) > 100)
        assertTrue("the second row is drawn, and not left blank: ${inked(1)}", inked(1) > 100)
    }

    @Test
    fun `switching fonts makes a new layer`() {
        val state = WinampState()
        val outline = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3)
        val skinned = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3, textSheet)

        assertNotSame(outline, skinned)
        assertSame(skinned, rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3, textSheet))
    }

    @Test
    fun `taller rows put each title one row height under the last, in either font`() {
        val state = WinampState().apply { currentIndex = -1 }
        val tall = PlaylistLayout(layout.height, rowH = 25)

        for (font in listOf(null, textSheet)) {
            val scale = 2
            val layer = rasterizer.textLayer(state, tall, PleditStyle.DEFAULT, scale, font)
            val per = layer.width / (tall.textRight - tall.textLeft)
            val pixels = layer.toPixelMap()

            fun inked(
                from: Int,
                until: Int,
            ) = (from * per until until * per).sumOf { y -> (0 until layer.width).count { x -> pixels[x, y].alpha > 0f } }
            assertTrue("the first row is in the first 25 px", inked(3, 28) > 50)
            assertTrue("the second row is in the next 25 px", inked(28, 53) > 50)
            // classic rows would put a second title here, where a tall row has only its margin
            assertEquals("nothing is written between the titles", 0, inked(3 + 19, 3 + 25 + 4))
        }
    }

    @Test
    fun `a change of row height makes a new layer`() {
        val state = WinampState()
        val classic = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3, textSheet)
        val tall = rasterizer.textLayer(state, PlaylistLayout(layout.height, rowH = 19), PleditStyle.DEFAULT, 3, textSheet)

        assertNotSame(classic, tall)
    }

    @Test
    fun `in the skin's font a title too long for its row ends on a whole letter, short of the duration`() {
        val long = Track("a", "The Marquee Scrollers", "This Title Is Intentionally Way Too Long To Fit", 263_000L)
        val short = Track("b", "Skin Deep", "Base", 263_000L)
        val state =
            WinampState().apply {
                playlist = listOf(long, short)
                currentIndex = -1
            }
        val layer = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, 3, textSheet)
        val pixels = layer.toPixelMap()
        val durationLeft = layer.width - 3 - "4:23".length * 5
        // whole 5 px letters from the 2 px inset, ending 4 px or more before the duration
        val titleRight = 2 + (durationLeft - 4 - 2) / 5 * 5

        fun inked(
            row: Int,
            columns: IntRange,
        ) = (3 + row * 13 until 3 + (row + 1) * 13).sumOf { y -> columns.count { x -> pixels[x, y].alpha > 0f } }
        assertEquals("nothing between the title and the duration", 0, inked(0, titleRight until durationLeft))
        assertTrue("the title runs up to its last letter: an ellipsis", inked(0, titleRight - 5 until titleRight) in 1..4)
        assertTrue("the duration is drawn", inked(0, durationLeft until layer.width) > 10)
        // a title that fits is not marked as cut: its row is empty where the long one ends
        assertEquals(0, inked(1, titleRight - 5 until titleRight))
    }
}
