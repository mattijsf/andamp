// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The readouts in the playlist's bottom bar are drawn inside the plates the
 * skin draws for them.
 *
 * The rows come from webamp (`.playlist-running-time-display` at top:10 and `.mini-time`
 * at top:23, both inside the 150px bottom-right corner).
 *
 * The tests check containment, not centering: how much room a well leaves around its
 * digits is the skin's choice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35])
class PlaylistBottomBarTest {
    private companion object {
        /** Room for a skin's own dithering, far below the contrast of text on a plate. */
        const val PLATE_TOLERANCE = 40
    }

    private lateinit var skin: Skin

    @Before
    fun loadSkin() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext())
    }

    private fun render(
        width: Int,
        height: Int,
        block: DrawScope.() -> Unit,
    ): Bitmap {
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(image),
            Size(width.toFloat(), height.toFloat()),
            block,
        )
        return image.asAndroidBitmap()
    }

    private fun playlist(): Bitmap {
        val state =
            WinampState().apply {
                transport = Transport.Playing
                currentTimeSec = 104
                blinkOn = true
            }
        val layout = PlaylistLayout.ofSegments(PlaylistLayout.MIN_SEGMENTS)
        val text =
            PlaylistTextRasterizer(
                Typeface.createFromAsset(
                    ApplicationProvider.getApplicationContext<android.content.Context>().assets,
                    PlaylistTextRasterizer.ASSET_PATH,
                ),
            )
        return render(PL_W, layout.height) { drawPlaylistWindow(skin, state, layout, text, scale = 1) }
    }

    /**
     * The plate's own color, read from the art, since the color belongs to the skin.
     *
     * The middle of the box is inside the panel by construction: the boxes below are the
     * plate and a little of its surround.
     */
    private fun plateColour(
        localX0: Int,
        localX1: Int,
        localY0: Int,
        localY1: Int,
    ): Int {
        val art = skin[Sheet.PLEDIT].toPixelMap()
        val sprite = SpriteMap.PLAYLIST_BOTTOM_RIGHT_CORNER
        return art[sprite.x + (localX0 + localX1) / 2, sprite.y + (localY0 + localY1) / 2].toArgb()
    }

    private fun far(
        a: Int,
        b: Int,
    ): Boolean {
        val d =
            listOf(16, 8, 0).sumOf { shift ->
                kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
            }
        return d > PLATE_TOLERANCE
    }

    /** The rows the plate covers inside the bottom-right corner's art. */
    private fun plateRows(
        localX0: Int,
        localX1: Int,
        localY0: Int,
        localY1: Int,
    ): IntRange {
        val art = skin[Sheet.PLEDIT].toPixelMap()
        val sprite = SpriteMap.PLAYLIST_BOTTOM_RIGHT_CORNER
        val plate = plateColour(localX0, localX1, localY0, localY1)
        val rows =
            (localY0 until localY1).filter { y ->
                (localX0 until localX1).all { x -> !far(art[sprite.x + x, sprite.y + y].toArgb(), plate) }
            }
        return rows.first()..rows.last()
    }

    /** The rows of [x0]..[x1] in the rendered window that carry text rather than plate. */
    private fun litRows(
        bitmap: Bitmap,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        plate: Int,
    ): IntRange {
        val rows =
            (y0 until y1).filter { y ->
                (x0 until x1).any { x -> far(bitmap.getPixel(x, y), plate) }
            }
        return rows.first()..rows.last()
    }

    @Test
    fun `the running time sits inside its plate`() {
        val bitmap = playlist()
        val bottom = bitmap.height - Dest.PL_BOTTOM_H
        // inside the wide plate, clear of its edges
        val plate = plateRows(localX0 = 20, localX1 = 90, localY0 = 0, localY1 = 20)

        // only inside the plate, and only over its own rows: the bar around it
        // is another color and the LIST OPTS button beside it is lit art
        val text = litRows(bitmap, 132, 218, bottom + plate.first, bottom + plate.last + 1, plateColour(20, 90, 0, 20))

        assertTrue(
            "the running time is drawn inside its well at ${bottom + plate.first}..${bottom + plate.last}, " +
                "at rows ${text.first}..${text.last}",
            text.first >= bottom + plate.first && text.last <= bottom + plate.last,
        )
    }

    @Test
    fun `the mini time sits inside its own plate`() {
        val bitmap = playlist()
        val bottom = bitmap.height - Dest.PL_BOTTOM_H
        // the small plate the mini transport's readout sits in
        val plate = plateRows(localX0 = 66, localX1 = 80, localY0 = 20, localY1 = 34)

        val text = litRows(bitmap, 191, 218, bottom + plate.first, bottom + plate.last + 1, plateColour(66, 80, 20, 34))

        assertTrue(
            "the mini time is drawn inside its plate at ${bottom + plate.first}..${bottom + plate.last}, " +
                "at rows ${text.first}..${text.last}",
            text.first >= bottom + plate.first && text.last <= bottom + plate.last,
        )
    }
}
