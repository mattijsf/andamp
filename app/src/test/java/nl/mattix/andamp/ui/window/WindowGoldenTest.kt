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
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.RowHeight
import nl.mattix.andamp.state.SkinEntry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshot tests: the frames and lists of the windows Andamp draws itself, rendered
 * at scale 1 with the bundled base skin, compared against PNGs in src/test/snapshots.
 *
 * Bless changed goldens with `./gradlew :app:recordRoborazziDebug`; the gate
 * the pre-push hook runs includes `verifyRoborazziDebug`. State fixtures must
 * stay fully deterministic — fixed times, no clocks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WindowGoldenTest {
    companion object {
        private const val SNAPSHOT_DIR = "src/test/snapshots"
    }

    private lateinit var skin: Skin

    @Before
    fun loadSkin() {
        // per-test: the Robolectric environment (and its asset manager) is per-method
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

    @Test
    fun `milkdrop window frame`() {
        // the generic frame only; the visualizer surface is a TextureView and
        // is not part of the skin canvas
        val height = milkdropHeight(GenFrame)
        render(MILKDROP_W, height) {
            with(GenWindow) { drawGenFrame(skin, MILKDROP_W, height, MILKDROP_TITLE) }
        }.captureRoboImage("$SNAPSHOT_DIR/milkdrop_window.png")
    }

    @Test
    fun `skin manager list rows`() {
        // rows share the playlist's metrics; the golden catches a one-pixel drift
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rasterizer = SkinListRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
        val entries =
            listOf(
                SkinEntry.BASE,
                SkinEntry("aaaa", "Nucleo-NLog-2G1.wsz", 120_000),
                SkinEntry("bbbb", "Steel_This_Amp_v5.wsz", 98_000),
                SkinEntry("cccc", "A name long enough to run into the REM column and be clipped.wsz", 40_000),
            )
        val frame = PleditWindowFrame
        val width = SkinManagerLayout.listWidth(frame)
        val height = SkinManagerLayout().listHeight()
        render(width, height) {
            drawSkinList(rasterizer, entries, scroll = 0, currentId = "aaaa", style = skin.pledit, scale = 1, frame = frame)
        }.captureRoboImage("$SNAPSHOT_DIR/skin_manager_rows.png")

        // held at a height between two rows: the list's background runs on under the last one
        val held = SkinManagerLayout(slack = 5)
        render(width, held.listHeight()) {
            drawSkinList(
                rasterizer,
                entries,
                scroll = 0,
                currentId = "aaaa",
                style = skin.pledit,
                scale = 1,
                frame = frame,
                layout = held,
            )
        }.captureRoboImage("$SNAPSHOT_DIR/skin_manager_slack.png")

        // taller rows: the band and the names step by the row the listener picked
        val large = SkinManagerLayout(4, rowH = RowHeight.LARGE.px)
        render(width, large.listHeight()) {
            drawSkinList(
                rasterizer,
                entries,
                scroll = 0,
                currentId = "aaaa",
                style = skin.pledit,
                scale = 1,
                frame = frame,
                layout = large,
            )
        }.captureRoboImage("$SNAPSHOT_DIR/skin_manager_large_rows.png")
    }

    @Test
    fun `playlist-art window frame`() {
        // what a skin without GEN.BMP wears: playlist chrome, plain bottom
        // filler (no baked ADD/SUB/SEL buttons), title in the skin's own font
        val height = milkdropHeight(PleditWindowFrame)
        render(MILKDROP_W, height) {
            with(PleditFrame) { drawPleditFrame(skin, MILKDROP_W, height, "SKINS") }
        }.captureRoboImage("$SNAPSHOT_DIR/pledit_window_frame.png")
    }
}
