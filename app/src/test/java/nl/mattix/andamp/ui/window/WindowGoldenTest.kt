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
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinDist
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.skin.Sprite
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.ListFont
import nl.mattix.andamp.state.RowHeight
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshot tests: each window's draw function rendered at scale 1
 * with the bundled base skin, compared against PNGs in src/test/snapshots.
 *
 * Bless changed goldens with `./gradlew :app:recordRoborazziDebug`; the gate
 * the pre-push hook runs includes `verifyRoborazziDebug`. State fixtures must
 * stay fully deterministic — fixed times, zeroed visualizer, no clocks.
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

    private fun measurer(): PlaylistTextRasterizer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PlaylistTextRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
    }

    // the playlist's rows are in the outline font in these two; the skin's font has goldens of its own
    private fun stoppedState() = WinampState().apply { listFont = ListFont.REGULAR }

    private fun playingState() =
        WinampState().apply {
            listFont = ListFont.REGULAR
            transport = Transport.Playing
            currentIndex = 3
            currentTimeSec = 83
            volume = 78
            balance = -40
            shuffle = true
            timeRemaining = false
            selectedRows = setOf(3)
            preamp = 45
            for (i in 0 until 10) eqBands[i] = i * 7
        }

    @Test
    fun `main window stopped`() {
        render(MAIN_W, MAIN_H) { drawMainWindow(skin, stoppedState()) }
            .captureRoboImage("$SNAPSHOT_DIR/main_window_stopped.png")
    }

    @Test
    fun `main window playing`() {
        render(MAIN_W, MAIN_H) { drawMainWindow(skin, playingState()) }
            .captureRoboImage("$SNAPSHOT_DIR/main_window_playing.png")
    }

    /**
     * The live path: the base skin rebuilt from its template and bound to the palette it
     * shipped with draws the same pixels as the file. The golden shows a drift in the
     * assembly as a picture; the pixel compare in the same test asserts it.
     */
    @Test
    fun `main window live baseline`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val live = LiveSkins { _, _ -> SkinDist.baselineScheme("dark") }.build(context, BundledSkins.BASE)!!

        val bitmap = render(MAIN_W, MAIN_H) { drawMainWindow(live, playingState()) }

        bitmap.captureRoboImage("$SNAPSHOT_DIR/main_window_live_baseline.png")
        val static = render(MAIN_W, MAIN_H) { drawMainWindow(skin, playingState()) }
        assertArrayEquals(pixels(static), pixels(bitmap))
    }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    /** A surface that shows one window at a time has neither open beside the main window. */
    @Test
    fun `the EQ and PL buttons are drawn unlit where they are told to be, whatever the phone has open`() {
        val open =
            playingState().apply {
                eqVisible = true
                plVisible = true
            }
        val closed =
            playingState().apply {
                eqVisible = false
                plVisible = false
            }

        val unlit = render(MAIN_W, MAIN_H) { drawMainWindow(skin, open, equalizerOpen = false, playlistOpen = false) }

        assertArrayEquals(pixels(render(MAIN_W, MAIN_H) { drawMainWindow(skin, closed) }), pixels(unlit))
        assertFalse(pixels(render(MAIN_W, MAIN_H) { drawMainWindow(skin, open) }).contentEquals(pixels(unlit)))
    }

    /** The pixels of [from] in a patch as large as [art], with its corner at [x], [y]. */
    private fun patch(
        from: Bitmap,
        x: Int,
        y: Int,
        art: Sprite,
    ) = IntArray(art.w * art.h).also { from.getPixels(it, 0, art.w, x, y, art.w, art.h) }

    /** [art] as MONOSTER.BMP holds it. */
    private fun lamp(art: Sprite) = patch(skin[Sheet.MONOSTER].asAndroidBitmap(), art.x, art.y, art)

    /** The mono and the stereo lamp as the main window shows them for [state]. */
    private fun lamps(state: WinampState): Pair<IntArray, IntArray> {
        val window = render(MAIN_W, MAIN_H) { drawMainWindow(skin, state) }
        return patch(window, Dest.MONO.x, Dest.MONO.y, SpriteMap.MAIN_MONO) to
            patch(window, Dest.STEREO.x, Dest.STEREO.y, SpriteMap.MAIN_STEREO)
    }

    @Test
    fun `one channel lights the mono lamp alone`() {
        val (mono, stereo) = lamps(playingState().apply { streamChannels = 1 })

        assertArrayEquals(lamp(SpriteMap.MAIN_MONO_SELECTED), mono)
        assertArrayEquals(lamp(SpriteMap.MAIN_STEREO), stereo)
    }

    @Test
    fun `two channels or more light the stereo lamp alone`() {
        for (channels in listOf(2, 6)) {
            val (mono, stereo) = lamps(playingState().apply { streamChannels = channels })

            assertArrayEquals("$channels channels", lamp(SpriteMap.MAIN_MONO), mono)
            assertArrayEquals("$channels channels", lamp(SpriteMap.MAIN_STEREO_SELECTED), stereo)
        }
    }

    @Test
    fun `an unknown channel count lights the stereo lamp while playing`() {
        val (mono, stereo) = lamps(playingState())

        assertArrayEquals(lamp(SpriteMap.MAIN_MONO), mono)
        assertArrayEquals(lamp(SpriteMap.MAIN_STEREO_SELECTED), stereo)
    }

    @Test
    fun `a paused track keeps its lamp and a stopped one has neither`() {
        val paused =
            playingState().apply {
                transport = Transport.Paused
                streamChannels = 1
            }
        val (pausedMono, _) = lamps(paused)
        val (stoppedMono, stoppedStereo) = lamps(stoppedState().apply { streamChannels = 1 })

        assertArrayEquals(lamp(SpriteMap.MAIN_MONO_SELECTED), pausedMono)
        assertArrayEquals(lamp(SpriteMap.MAIN_MONO), stoppedMono)
        assertArrayEquals(lamp(SpriteMap.MAIN_STEREO), stoppedStereo)
    }

    @Test
    fun `main window with always on top lit`() {
        // Winamp's A stays lit for as long as it is on, not only while held
        render(MAIN_W, MAIN_H) {
            drawMainWindow(skin, playingState().apply { alwaysOnTop = true })
        }.captureRoboImage("$SNAPSHOT_DIR/main_window_always_on_top.png")
    }

    @Test
    fun `main window with double size lit`() {
        // the D stays lit for as long as Double Size is on
        render(MAIN_W, MAIN_H) {
            drawMainWindow(skin, playingState().apply { doubleSize = true })
        }.captureRoboImage("$SNAPSHOT_DIR/main_window_double_size.png")
    }

    @Test
    fun `main window with a held letter beside the two that stay lit`() {
        // each lit letter is drawn over the bar on its own, so they light together
        render(MAIN_W, MAIN_H) {
            drawMainWindow(
                skin,
                playingState().apply {
                    alwaysOnTop = true
                    doubleSize = true
                    pressedWidget = "main.clutter.v"
                },
            )
        }.captureRoboImage("$SNAPSHOT_DIR/main_window_clutter_lit_together.png")
    }

    @Test
    fun `main window shaded`() {
        render(MAIN_W, SHADE_H) { drawMainShade(skin, playingState()) }
            .captureRoboImage("$SNAPSHOT_DIR/main_window_shade.png")
    }

    /**
     * The strip the shaded bar keeps for the visualizer, with a signal in it.
     *
     * Its own golden because it is its own canvas: the shaded window's PNG cannot show it,
     * the overlay draws it. Levels are planted, so the fixture is deterministic.
     */
    @Test
    fun `shaded visualizer strip, analyzer`() {
        val s = playingState().apply { plantLevels() }

        render(VisBox.SHADE.w, VisBox.SHADE.h) { drawVisualizerBars(skin, s, VisBox.SHADE) }
            .captureRoboImage("$SNAPSHOT_DIR/main_shade_vis_analyzer.png")
    }

    /** The same strip, the other style - the one a tap on it switches to. */
    @Test
    fun `shaded visualizer strip, oscilloscope`() {
        val s =
            playingState().apply {
                plantLevels()
                visMode = nl.mattix.andamp.state.VisMode.Oscilloscope
            }

        render(VisBox.SHADE.w, VisBox.SHADE.h) { drawVisualizerBars(skin, s, VisBox.SHADE) }
            .captureRoboImage("$SNAPSHOT_DIR/main_shade_vis_oscilloscope.png")
    }

    /** A staircase of levels and a wave, so every row of the ramp is exercised. */
    private fun WinampState.plantLevels() {
        for (i in 0 until 19) {
            visLevels[i] = (i * 16f / 18f)
            visPeaks[i] = (16 - i).toFloat()
        }
        for (x in 0 until WinampState.WAVE_COLUMNS) {
            visWave[x] = 8 + ((x % 8) - 4)
        }
    }

    @Test
    fun `eq window shaded`() {
        render(EQ_W, SHADE_H) { drawEqShade(skin, playingState()) }
            .captureRoboImage("$SNAPSHOT_DIR/eq_window_shade.png")
    }

    @Test
    fun `playlist window two steps wider`() {
        val layout = PlaylistLayout.ofSegments(4, PlaylistLayout.widthOfCols(2))
        render(layout.width, layout.height) { drawPlaylistWindow(skin, playingState(), layout, measurer()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_wide.png")
    }

    @Test
    fun `playlist window shaded`() {
        render(PL_W, SHADE_H) { drawPlaylistShade(skin, playingState()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_shade.png")
    }

    /**
     * Winamp's shaded playlist says `[NO FILE]` when there is nothing queued; an empty
     * strip would read as a window that failed to draw.
     */
    @Test
    fun `playlist window shaded with nothing in it`() {
        val empty = stoppedState().apply { playlist = emptyList() }

        render(PL_W, SHADE_H) { drawPlaylistShade(skin, empty) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_shade_empty.png")
    }

    @Test
    fun `eq window flat`() {
        render(EQ_W, EQ_H) { drawEqWindow(skin, stoppedState()) }
            .captureRoboImage("$SNAPSHOT_DIR/eq_window_flat.png")
    }

    @Test
    fun `eq window with curve`() {
        render(EQ_W, EQ_H) { drawEqWindow(skin, playingState()) }
            .captureRoboImage("$SNAPSHOT_DIR/eq_window_curve.png")
    }

    @Test
    fun `playlist window at minimum height`() {
        val layout = PlaylistLayout(116)
        render(PL_W, layout.height) { drawPlaylistWindow(skin, stoppedState(), layout, measurer()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_min.png")
    }

    @Test
    fun `playlist window tall with selection`() {
        val layout = PlaylistLayout.forAvailableHeight(400)
        render(PL_W, layout.height) { drawPlaylistWindow(skin, playingState(), layout, measurer()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_tall.png")
    }

    @Test
    fun `playlist window in the skin's font`() {
        val layout = PlaylistLayout.ofSegments(4)
        val state = playingState().apply { listFont = ListFont.SKIN }
        render(PL_W, layout.height) { drawPlaylistWindow(skin, state, layout, measurer()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_skin_font.png")
    }

    @Test
    fun `playlist window with large rows`() {
        // the band, the titles and the durations all step by the taller row
        val layout = PlaylistLayout.ofSegments(6, rowH = RowHeight.LARGE.px)
        render(PL_W, layout.height) { drawPlaylistWindow(skin, playingState(), layout, measurer()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_large_rows.png")
    }

    @Test
    fun `playlist window with roomy rows in the skin's font`() {
        val layout = PlaylistLayout.ofSegments(6, rowH = RowHeight.ROOMY.px)
        val state = playingState().apply { listFont = ListFont.SKIN }
        render(PL_W, layout.height) { drawPlaylistWindow(skin, state, layout, measurer()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_roomy_rows_skin_font.png")
    }

    @Test
    fun `playlist window held at a height between two segments`() {
        // the frame's ninth tile and the list's last row are cut where the window ends
        val layout = PlaylistLayout(20 + 8 * 29 + 11 + 38)
        render(PL_W, layout.height) { drawPlaylistWindow(skin, playingState(), layout, measurer()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_exact_height.png")
    }

    @Test
    fun `playlist window with the remove menu expanded`() {
        val layout = PlaylistLayout(116)
        val state = stoppedState().apply { openMenu = "pl.menu.rem" }
        render(PL_W, layout.height) { drawPlaylistWindow(skin, state, layout, measurer()) }
            .captureRoboImage("$SNAPSHOT_DIR/playlist_window_menu_open.png")
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
