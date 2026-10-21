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
import nl.mattix.andamp.state.EnglishWords
import nl.mattix.andamp.state.ListFont
import nl.mattix.andamp.state.RowHeight
import nl.mattix.andamp.state.WinampState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots of the media library window: its content area drawn at scale 1 with the
 * bundled base skin, over hand-rolled shelves, compared against PNGs in src/test/snapshots.
 * See [WindowGoldenTest] for blessing changed goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LibraryGoldenTest {
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

    // --- the media library: deterministic fixtures over a hand-rolled shelf ---

    /** Two artists, and any [more]; the first has one album of two tracks. */
    private class GoldenShelf(
        override val available: Boolean = true,
        private val more: List<String> = emptyList(),
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
            ) +
                more.mapIndexed { at, name ->
                    nl.mattix.andamp.core.model
                        .LibraryArtist("m$at", name, albumCount = 1)
                }

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

    /**
     * A library over [GoldenShelf], in the outline font unless [font] says otherwise, so the
     * goldens that came before the skin's font still show what they showed.
     */
    private fun libraryFixture(
        available: Boolean = true,
        font: ListFont = ListFont.REGULAR,
        more: List<String> = emptyList(),
        seed: (nl.mattix.andamp.state.PlaylistLibrary, nl.mattix.andamp.state.StationStore) -> Unit = { _, _ -> },
    ): Pair<nl.mattix.andamp.state.LibraryOps, WinampState> {
        val state = WinampState().apply { listFont = font }
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
                .LibraryOps({
                    GoldenShelf(available, more)
                }, facade, state, scope, lists, stations, io = kotlinx.coroutines.Dispatchers.Unconfined, words = EnglishWords)
        ops.open()
        return ops to state
    }

    private fun libraryRasterizer(): LibraryRasterizer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return LibraryRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH), context.resources)
    }

    private fun renderLibrary(
        name: String,
        ops: nl.mattix.andamp.state.LibraryOps,
        state: WinampState,
        cols: Int = 0,
        slack: Int = 0,
        rowH: Int = LibraryLayout.ROW_H,
        source: String? = null,
        sourceMark: nl.mattix.andamp.state.SourceMark? = null,
    ) {
        val frame = GenFrame
        val layout = LibraryLayout(8, LibraryLayout.widthOfCols(cols), slack, rowH)
        render(layout.listWidth(frame), layout.contentH) {
            drawLibraryContent(libraryRasterizer(), ops, state, skin, 1, frame, layout, source, sourceMark)
        }.captureRoboImage("$SNAPSHOT_DIR/$name.png")
    }

    @Test
    fun `library artists with header and status bar`() {
        val (ops, state) = libraryFixture()
        renderLibrary("library_artists", ops, state)
    }

    @Test
    fun `a library held at a height between two rows keeps its bar at the bottom`() {
        // the rows' area and the scrollbar's rail are seven pixels taller than the rows
        val (ops, state) = libraryFixture()
        renderLibrary("library_slack", ops, state, slack = 7)
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
    fun `library tracks with large rows`() {
        // the tabs, the header and the bar grow with the rows, so they are as easy to tap
        val (ops, state) = libraryFixture()
        ops.tapRow(0) // Autechre
        ops.tapRow(0) // Amber
        state.librarySelected = 0
        renderLibrary("library_large_rows", ops, state, rowH = RowHeight.LARGE.px)
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
    fun `library saved list with the delete cell`() {
        val (ops, state) =
            libraryFixture { lists, _ ->
                lists.save(
                    "Road trip",
                    listOf(
                        nl.mattix.andamp.core.model
                            .Track("r1", "Autechre", "Foil", 366_000, uri = "content://1"),
                        nl.mattix.andamp.core.model
                            .Track("r2", "Autechre", "Montreal", 456_000, uri = "content://2"),
                    ),
                )
            }
        ops.switchCategory(
            nl.mattix.andamp.state.LibraryOps.Category.LISTS,
        )
        ops.tapRow(0)
        renderLibrary("library_list_saved", ops, state)
    }

    @Test
    fun `library artists in the skin's font`() {
        // the tabs, the header, the rows and the bar in TEXT.BMP's letters
        val (ops, state) = libraryFixture(font = ListFont.SKIN)
        renderLibrary("library_skin_font_artists", ops, state)
    }

    @Test
    fun `library tracks in the skin's font with a selection and the playing row`() {
        val (ops, state) = libraryFixture(font = ListFont.SKIN)
        ops.tapRow(0) // Autechre
        ops.tapRow(0) // Amber
        state.librarySelected = 0
        state.playlist =
            listOf(
                nl.mattix.andamp.core.model
                    .Track("g-t1", "Autechre", "Foil", 366_000, uri = "content://1"),
                nl.mattix.andamp.core.model
                    .Track("g-t2", "Autechre", "Montreal", 456_000, uri = "content://2"),
            )
        state.currentIndex = 1
        renderLibrary("library_skin_font_tracks", ops, state)
    }

    @Test
    fun `library search in the skin's font with the caret and the clear cell`() {
        val (ops, state) = libraryFixture(font = ListFont.SKIN)
        ops.enterSearch()
        ops.setQuery("autech", 6)
        state.marqueeStep = 0
        renderLibrary("library_skin_font_search", ops, state)
    }

    @Test
    fun `library rows the skin's font cannot spell keep the outline font`() {
        // a long name ends in the font's ellipsis; a Cyrillic one is drawn in the outline font
        val (ops, state) =
            libraryFixture(
                font = ListFont.SKIN,
                more = listOf("Godspeed You! Black Emperor and a name that does not fit", "\u041a\u0438\u043d\u043e"),
            )
        renderLibrary("library_skin_font_fallback", ops, state)
    }

    @Test
    fun `library in the skin's font with large rows and large furniture`() {
        val (ops, state) = libraryFixture(font = ListFont.SKIN)
        ops.tapRow(0) // Autechre
        ops.tapRow(0) // Amber
        state.librarySelected = 0
        renderLibrary("library_skin_font_large_rows", ops, state, rowH = RowHeight.LARGE.px)
    }

    @Test
    fun `library shows the letter of the top row while its scroll handle is held`() {
        val names = listOf("Coil", "Cluster", "Can", "Faust", "Kraftwerk", "Laika", "Moebius", "Neu!", "Plaid", "Seefeel")
        val (ops, state) = libraryFixture(font = ListFont.SKIN, more = names)
        state.libraryScroll = 4 // Can
        state.pressedWidget = "library.scroll"
        renderLibrary("library_skin_font_letter", ops, state)
    }

    @Test
    fun `library offers PLAY and ADD inside an artist`() {
        val (ops, state) = libraryFixture(font = ListFont.SKIN)
        ops.tapRow(0) // Autechre
        renderLibrary("library_skin_font_artist_play", ops, state)
    }

    /** A shelf whose search finds an artist, an album and two tracks, in sections. */
    private class SectionedShelf :
        nl.mattix.andamp.core.playback.BrowseSource,
        nl.mattix.andamp.state.SectionedSearch {
        override val capabilities =
            nl.mattix.andamp.core.model
                .BrowseCapabilities(canSearch = true)
        override val available = true
        private val autechre =
            nl.mattix.andamp.core.model
                .LibraryArtist("a1", "Autechre", albumCount = 1)
        private val amber =
            nl.mattix.andamp.core.model
                .LibraryAlbum("l1", "Amber", "Autechre", year = 1994)
        private val tracks =
            listOf(
                nl.mattix.andamp.core.model
                    .Track("g-t1", "Autechre", "Foil", 366_000, uri = "content://1"),
                nl.mattix.andamp.core.model
                    .Track("g-t2", "Autechre", "Montreal", 456_000, uri = "content://2"),
            )

        override suspend fun artists() = listOf(autechre)

        override suspend fun albums(artistId: String?) = listOf(amber)

        override suspend fun tracks(albumId: String) = tracks

        override suspend fun searchSections(
            query: String,
            limit: Int,
        ) = nl.mattix.andamp.state.SectionedSearch
            .Sections(listOf(autechre), listOf(amber), tracks)
    }

    @Test
    fun `library search results in sections, in the skin's font`() {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val clock = kotlinx.coroutines.test.StandardTestDispatcher(scheduler)
        val state = WinampState().apply { listFont = ListFont.SKIN }
        val facade =
            nl.mattix.andamp.core.player
                .PlayerFacade(
                    nl.mattix.andamp.backend.mock
                        .MockBackend(emptyList(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)),
                )
        val ops =
            nl.mattix.andamp.state.LibraryOps(
                { SectionedShelf() },
                facade,
                state,
                kotlinx.coroutines.CoroutineScope(clock),
                nl.mattix.andamp.state
                    .PlaylistLibrary(java.io.File(goldenFiles, "sections-playlists")),
                nl.mattix.andamp.state
                    .StationStore(java.io.File(goldenFiles, "sections-stations.m3u")),
                io = clock,
                words = EnglishWords,
            )
        ops.open()
        scheduler.advanceUntilIdle()
        ops.enterSearch()
        ops.setQuery("aut", 3)
        scheduler.advanceUntilIdle()
        state.marqueeStep = 0
        state.librarySelected = 5 // Foil
        renderLibrary("library_skin_font_sections", ops, state)
    }

    @Test
    fun `library of every source together, named in the header's corner, albums marked by source`() =
        allSources("library_skin_font_all_sources", ListFont.SKIN)

    @Test
    fun `library of every source together in the outline font, the phone's mark drawn from its path`() =
        allSources("library_all_sources", ListFont.REGULAR)

    @Test
    fun `every source together lists each source's lists with its mark, the app's own lists without`() =
        allSources("library_all_sources_lists", ListFont.SKIN, nl.mattix.andamp.state.LibraryOps.Category.LISTS)

    @Test
    fun `one source on show carries its mark before its name in the header's corner`() =
        allSources(
            "library_skin_font_one_source",
            ListFont.SKIN,
            source = "This Phone",
            sourceMark = nl.mattix.andamp.state.SourceMark.PHONE,
        )

    @Test
    fun `one source on show carries its mark before its name, in the outline font`() =
        allSources(
            "library_one_source",
            ListFont.REGULAR,
            source = "This Phone",
            sourceMark = nl.mattix.andamp.state.SourceMark.PHONE,
        )

    private fun allSources(
        name: String,
        font: ListFont,
        category: nl.mattix.andamp.state.LibraryOps.Category = nl.mattix.andamp.state.LibraryOps.Category.ALBUMS,
        source: String = "All Sources",
        sourceMark: nl.mattix.andamp.state.SourceMark? = null,
    ) {
        val shelf =
            nl.mattix.andamp.core.playback.BrowseSourceContractTest
                .Shelf(
                    listOf(
                        nl.mattix.andamp.core.playback.BrowseSourceContractTest.ShelfArtist(
                            "Autechre",
                            listOf(
                                nl.mattix.andamp.core.playback.BrowseSourceContractTest
                                    .ShelfAlbum("Amber", 1994, listOf("Foil" to 1)),
                            ),
                        ),
                    ),
                )
        val server =
            nl.mattix.andamp.core.playback.BrowseSourceContractTest.Shelf(
                listOf(
                    nl.mattix.andamp.core.playback.BrowseSourceContractTest.ShelfArtist(
                        "Autechre",
                        listOf(
                            nl.mattix.andamp.core.playback.BrowseSourceContractTest
                                .ShelfAlbum("Amber", 1994, listOf("Foil" to 1)),
                            nl.mattix.andamp.core.playback.BrowseSourceContractTest
                                .ShelfAlbum("Tri Repetae", 1995, listOf("Dael" to 1)),
                        ),
                    ),
                ),
                playlists =
                    listOf(
                        nl.mattix.andamp.core.playback.BrowseSourceContractTest
                            .ShelfPlaylist("Late Night", listOf("Foil", "Dael")),
                    ),
            )
        val all =
            nl.mattix.andamp.state.AllLibrary {
                listOf(
                    nl.mattix.andamp.state.AllLibrary
                        .Member(
                            nl.mattix.andamp.state.MusicSource.LOCAL,
                            nl.mattix.andamp.state
                                .ShelfSource(shelf),
                            nl.mattix.andamp.state.SourceMark.PHONE,
                        ),
                    nl.mattix.andamp.state.AllLibrary.Member(
                        nl.mattix.andamp.state
                            .MusicSource("EXAMPLE", "Example"),
                        nl.mattix.andamp.state
                            .ShelfSource(server),
                        nl.mattix.andamp.state.SourceMark
                            .letter("Example"),
                    ),
                )
            }
        val state = WinampState().apply { listFont = font }
        val facade =
            nl.mattix.andamp.core.player
                .PlayerFacade(
                    nl.mattix.andamp.backend.mock
                        .MockBackend(emptyList(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)),
                )
        val ops =
            nl.mattix.andamp.state.LibraryOps(
                { all },
                facade,
                state,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                nl.mattix.andamp.state
                    .PlaylistLibrary(java.io.File(goldenFiles, "all-playlists")),
                nl.mattix.andamp.state
                    .StationStore(java.io.File(goldenFiles, "all-stations.m3u")),
                io = kotlinx.coroutines.Dispatchers.Unconfined,
                words = EnglishWords,
            )
        ops.open()
        ops.switchCategory(category)
        renderLibrary(name, ops, state, source = source, sourceMark = sourceMark)
    }
}
