// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Typeface
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.state.WinampState
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
    private val layout = PlaylistLayout(116)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        rasterizer = PlaylistTextRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
    }

    @Test
    fun `renders text pixels in the row colors at device scale`() {
        val state = WinampState()
        val scale = 3
        val layer = rasterizer.textLayer(state, layout, PleditStyle.DEFAULT, scale)
        assertTrue(layer.width == (layout.textRight - layout.textLeft) * scale)
        assertTrue(layer.height == layout.middleH * scale)
        val pixels = layer.toPixelMap()
        var textPixels = 0
        for (y in 0 until layer.height) {
            for (x in 0 until layer.width) {
                if (pixels[x, y] == PleditStyle.DEFAULT.normal || pixels[x, y] == PleditStyle.DEFAULT.current) textPixels++
            }
        }
        // antialiased edges blend, but glyph cores keep the exact PLEDIT color
        assertTrue("over 100 text pixels are in PLEDIT colors: $textPixels", textPixels > 100)
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
}
