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
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinDist
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertArrayEquals
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

    private fun stoppedState() = WinampState()

    private fun playingState() =
        WinampState().apply {
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

    @Test
    fun `main window with always on top lit`() {
        // Winamp's A stays lit for as long as it is on, not only while held
        render(MAIN_W, MAIN_H) {
            drawMainWindow(skin, playingState().apply { alwaysOnTop = true })
        }.captureRoboImage("$SNAPSHOT_DIR/main_window_always_on_top.png")
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
    }

    // --- the media library: deterministic fixtures over a hand-rolled shelf ---

    /** Two artists; the first has one album of two tracks. */
    private class GoldenShelf(
        override val available: Boolean = true,
    ) : nl.mattix.andamp.core.playback.BrowseSource {
        override val capabilities =
            nl.mattix.andamp.core.model
                .BrowseCapabilities(canSearch = true)

        override suspend fun artists() =
            listOf(
                nl.mattix.andamp.core.model
                    .LibraryArtist("a1", "Autechre", albumCount = 1),
                nl.mattix.andamp.core.model
                    .LibraryArtist("a2", "Boards of Canada", albumCount = 1),
            )

        override suspend fun albums(artistId: String?) =
            listOf(
                nl.mattix.andamp.core.model
                    .LibraryAlbum("l1", "Amber", "Autechre", year = 1994),
            )

        override suspend fun tracks(albumId: String) =
            listOf(
                nl.mattix.andamp.core.model
                    .Track("g-t1", "Autechre", "Foil", 366_000, uri = "content://1"),
                nl.mattix.andamp.core.model
                    .Track("g-t2", "Autechre", "Montreal", 456_000, uri = "content://2"),
            )

        override suspend fun search(
            query: String,
            limit: Int,
        ): List<nl.mattix.andamp.core.model.Track> {
            // never answers: the search golden captures the field itself, and a
            // debounced fetch landing mid-capture would race the pixels
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private val goldenFiles = java.io.File(System.getProperty("java.io.tmpdir"), "andamp-golden-${System.nanoTime()}")

    private fun libraryFixture(
        available: Boolean = true,
        seed: (nl.mattix.andamp.state.PlaylistLibrary, nl.mattix.andamp.state.StationStore) -> Unit = { _, _ -> },
    ): Pair<nl.mattix.andamp.state.LibraryOps, WinampState> {
        val state = WinampState()
        val scope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
            )
        val facade =
            nl.mattix.andamp.core.player
                .PlayerFacade(
                    nl.mattix.andamp.backend.mock
                        .MockBackend(emptyList(), scope),
                )
        val lists =
            nl.mattix.andamp.state
                .PlaylistLibrary(java.io.File(goldenFiles, "playlists"))
        val stations =
            nl.mattix.andamp.state
                .StationStore(java.io.File(goldenFiles, "stations.m3u"))
        seed(lists, stations)
        val ops =
            nl.mattix.andamp.state
                .LibraryOps({ GoldenShelf(available) }, facade, state, scope, lists, stations)
        ops.open()
        return ops to state
    }

    private fun libraryRasterizer(): LibraryRasterizer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return LibraryRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
    }

    private fun renderLibrary(
        name: String,
        ops: nl.mattix.andamp.state.LibraryOps,
        state: WinampState,
        cols: Int = 0,
    ) {
        val frame = GenFrame
        val layout = LibraryLayout(8, LibraryLayout.widthOfCols(cols))
        render(layout.listWidth(frame), layout.contentH) {
            drawLibraryContent(libraryRasterizer(), ops, state, skin, 1, frame, layout)
        }.captureRoboImage("$SNAPSHOT_DIR/$name.png")
    }

    @Test
    fun `library artists with header and status bar`() {
        val (ops, state) = libraryFixture()
        renderLibrary("library_artists", ops, state)
    }

    @Test
    fun `a widened library stretches its strip and its rows`() {
        // the tab strip spans the wider list, the status bar's cells stay
        // right-aligned, and the rows and scrollbar follow the new edge
        val (ops, state) = libraryFixture()
        renderLibrary("library_wide", ops, state, cols = 2)
    }

    @Test
    fun `library tracks with a selection and the playing row`() {
        val (ops, state) = libraryFixture()
        ops.tapRow(0) // Autechre
        ops.tapRow(0) // Amber
        state.librarySelected = 0
        // the playing track is the second row, in the current color
        state.playlist =
            listOf(
                nl.mattix.andamp.core.model
                    .Track("g-t1", "Autechre", "Foil", 366_000, uri = "content://1"),
                nl.mattix.andamp.core.model
                    .Track("g-t2", "Autechre", "Montreal", 456_000, uri = "content://2"),
            )
        state.currentIndex = 1
        renderLibrary("library_tracks", ops, state)
    }

    @Test
    fun `library asks for access instead of a black void`() {
        val (ops, state) = libraryFixture(available = false)
        renderLibrary("library_no_access", ops, state)
    }

    @Test
    fun `library search with a query and the caret`() {
        val (ops, state) = libraryFixture()
        ops.enterSearch()
        ops.setQuery("autech", 6)
        // marqueeStep pinned so the caret is deterministically on
        state.marqueeStep = 0
        renderLibrary("library_search", ops, state)
    }

    @Test
    fun `library radio with stations and the new cell`() {
        val (ops, state) =
            libraryFixture { _, stations ->
                stations.add("Groove Salad", "http://radio.example.org/groove")
                stations.add("Drone Zone", "http://radio.example.org/drone")
            }
        ops.switchCategory(
            nl.mattix.andamp.state.LibraryOps.Category.RADIO,
        )
        state.librarySelected = 0
        renderLibrary("library_radio", ops, state)
    }

    @Test
    fun `library empty lists shelf says how to fill it`() {
        val (ops, state) = libraryFixture()
        ops.switchCategory(
            nl.mattix.andamp.state.LibraryOps.Category.LISTS,
        )
        renderLibrary("library_lists_empty", ops, state)
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
