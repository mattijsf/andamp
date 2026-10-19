// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.ListFont
import nl.mattix.andamp.state.MarkPlace
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.SourceMark
import nl.mattix.andamp.state.WinampState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots of the playlist with each row's source marked, before its title or after
 * it, in the skin's font and in the outline font. See [WindowGoldenTest] for blessing them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlaylistMarksGoldenTest {
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
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), block)
        return image.asAndroidBitmap()
    }

    private fun measurer(): PlaylistTextRasterizer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PlaylistTextRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
    }

    /** Rows from the phone and from a source called Example, taking turns. */
    private fun state(
        font: ListFont,
        place: MarkPlace,
    ) = WinampState().apply {
        listFont = font
        listMarks = place
        sourceMarks = mapOf(MusicSource.LOCAL.id to SourceMark.PHONE, "EXAMPLE" to SourceMark.letter("Example"))
        playlist =
            List(6) { at ->
                val uri = if (at % 2 == 0) "content://media/external/audio/media/$at" else "example:track:$at"
                Track(
                    "t$at",
                    "Portishead",
                    listOf("Mysterons", "Sour Times", "Strangers", "It Could Be Sweet", "Wandering Star", "Roads")[at],
                    240_000L + at * 1000,
                    uri = uri,
                )
            }
        currentIndex = 1
    }

    private fun capture(
        name: String,
        font: ListFont,
        place: MarkPlace,
    ) {
        val layout = PlaylistLayout.ofSegments(4)
        render(PL_W, layout.height) { drawPlaylistWindow(skin, state(font, place), layout, measurer()) }
            .captureRoboImage("src/test/snapshots/$name.png")
    }

    @Test
    fun `playlist rows marked by source before their titles, in the skin's font`() =
        capture("playlist_marks_before_skin_font", ListFont.SKIN, MarkPlace.BEFORE)

    @Test
    fun `playlist rows marked by source in front of their lengths, in the skin's font`() =
        capture("playlist_marks_after_skin_font", ListFont.SKIN, MarkPlace.AFTER)

    @Test
    fun `playlist rows marked by source before their titles, in the outline font`() =
        capture("playlist_marks_before", ListFont.REGULAR, MarkPlace.BEFORE)
}
