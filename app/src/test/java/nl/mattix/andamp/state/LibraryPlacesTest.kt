// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the library keeps while the listener moves about in it: the place in each tab they
 * leave, PLAY and ADD inside an artist, the letter beside a dragged handle, and the word for a
 * source that stopped part way.
 */
class LibraryPlacesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val temp = TemporaryFolder()

    /** [names] as artists; the first has two records of two tracks each, the rest one empty record. */
    private class Shelf(
        private val names: List<String> = listOf("Autechre", "Boards of Canada"),
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities(canSearch = true)
        override val available = true

        override suspend fun artists() = names.mapIndexed { at, name -> LibraryArtist("a$at", name, albumCount = 1) }

        override suspend fun albums(artistId: String?) =
            when (artistId) {
                "a0" -> {
                    listOf(
                        LibraryAlbum("l1", "Amber", "Autechre", year = 1994),
                        LibraryAlbum("l2", "Tri Repetae", "Autechre", year = 1995),
                    )
                }

                null -> {
                    listOf(LibraryAlbum("l1", "Amber", "Autechre", year = 1994))
                }

                else -> {
                    listOf(LibraryAlbum("x-$artistId", "Other", "Other"))
                }
            }

        override suspend fun tracks(albumId: String) =
            when (albumId) {
                "l1" -> {
                    listOf(
                        Track("t1", "Autechre", "Foil", 6_000, uri = "content://1"),
                        Track("t2", "Autechre", "Montreal", 7_000, uri = "content://2"),
                    )
                }

                "l2" -> {
                    listOf(
                        Track("t3", "Autechre", "Dael", 6_000, uri = "content://3"),
                        Track("t4", "Autechre", "Clipper", 7_000, uri = "content://4"),
                    )
                }

                else -> {
                    emptyList()
                }
            }
    }

    private class Library(
        val ops: LibraryOps,
        val state: WinampState,
        val facade: PlayerFacade,
        val stations: StationStore,
    )

    private fun library(
        source: BrowseSource = Shelf(),
        whole: Boolean = true,
    ): Library {
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        val stations = StationStore(File(temp.newFolder("radio"), "stations.m3u"))
        val ops =
            LibraryOps(
                { source },
                facade,
                state,
                scope,
                PlaylistLibrary(temp.newFolder("playlists")),
                stations,
                io = Dispatchers.Unconfined,
                words = EnglishWords,
                wholeOf = { whole },
            )
        ops.open()
        return Library(ops, state, facade, stations)
    }

    // --- a place per tab ---

    @Test
    fun `a tab left deep comes back where it was, and up still climbs`() {
        val lib = library()
        lib.ops.tapRow(0) // Autechre

        lib.ops.switchCategory(LibraryOps.Category.ALBUMS)
        assertEquals("ALBUMS", lib.ops.header)
        lib.ops.switchCategory(LibraryOps.Category.ARTISTS)

        assertEquals("Autechre", lib.ops.header)
        assertEquals(1, lib.ops.depth)
        assertEquals(listOf("Amber", "Tri Repetae"), lib.ops.rows.map { it.label })
        lib.ops.up()
        assertEquals(listOf("Autechre", "Boards of Canada"), lib.ops.rows.map { it.label })
    }

    @Test
    fun `a tab comes back at the scroll and the selection it was left at`() {
        val lib = library(Shelf(List(30) { "Artist ${'A' + it}" }))
        lib.state.libraryScroll = 12

        lib.ops.switchCategory(LibraryOps.Category.ALBUMS)
        lib.ops.switchCategory(LibraryOps.Category.ARTISTS)
        assertEquals(12, lib.state.libraryScroll)

        lib.ops.tapRow(0)
        lib.ops.tapRow(0) // a record of tracks
        lib.ops.tapRow(1) // selects
        lib.ops.switchCategory(LibraryOps.Category.LISTS)
        lib.ops.switchCategory(LibraryOps.Category.ARTISTS)
        assertTrue(lib.ops.atTracks)
        assertEquals(1, lib.state.librarySelected)
    }

    @Test
    fun `the active tab tapped while deep goes to its root and keeps no place`() {
        val lib = library()
        lib.ops.tapRow(0)

        lib.ops.switchCategory(LibraryOps.Category.ARTISTS)
        lib.ops.switchCategory(LibraryOps.Category.ALBUMS)
        lib.ops.switchCategory(LibraryOps.Category.ARTISTS)

        assertEquals(0, lib.ops.depth)
        assertEquals("ARTISTS", lib.ops.header)
    }

    @Test
    fun `a search is not a place to come back to`() {
        val lib = library()
        lib.ops.enterSearch()

        lib.ops.switchCategory(LibraryOps.Category.ALBUMS)
        lib.ops.switchCategory(LibraryOps.Category.ARTISTS)

        assertFalse(lib.ops.searching)
        assertEquals(0, lib.ops.depth)
        assertEquals(listOf("Autechre", "Boards of Canada"), lib.ops.rows.map { it.label })
    }

    @Test
    fun `opening the window again forgets the places`() {
        val lib = library()
        lib.ops.tapRow(0)
        lib.ops.switchCategory(LibraryOps.Category.ALBUMS)

        lib.ops.open()
        lib.ops.switchCategory(LibraryOps.Category.ARTISTS)

        assertEquals(0, lib.ops.depth)
    }

    @Test
    fun `stations are read again on every visit, at the scroll they were left at`() {
        val lib = library()
        lib.stations.add("Example FM", "http://radio.example.com/stream")
        lib.stations.add("Radio Example", "https://example.com/fm")
        lib.ops.switchCategory(LibraryOps.Category.RADIO)
        lib.state.libraryScroll = 1

        lib.ops.switchCategory(LibraryOps.Category.ARTISTS)
        lib.stations.add("Third Example", "https://example.org/third")
        lib.ops.switchCategory(LibraryOps.Category.RADIO)

        assertEquals(listOf("Example FM", "Radio Example", "Third Example"), lib.ops.rows.map { it.label })
        assertEquals(1, lib.state.libraryScroll)
    }

    // --- PLAY and ADD inside an artist ---

    @Test
    fun `PLAY inside an artist plays every record, in order`() {
        val lib = library()
        assertFalse("a tab's root stands for the whole library", lib.ops.canPlayPage)
        lib.ops.tapRow(0) // Autechre

        assertTrue(lib.ops.canPlayPage)
        lib.ops.playSelection()

        assertEquals(Transport.Playing, lib.facade.state.value.transport)
        assertEquals(
            listOf("Foil", "Montreal", "Dael", "Clipper"),
            lib.facade.state.value.queue
                .map { it.title },
        )
    }

    @Test
    fun `ADD inside an artist enqueues every record and says how many`() {
        val lib = library()
        val before = lib.facade.state.value.queue.size
        lib.ops.tapRow(0)

        lib.ops.enqueueSelection()

        assertEquals(before + 4, lib.facade.state.value.queue.size)
        assertEquals("+4 QUEUED", lib.ops.flash)
    }

    @Test
    fun `PLAY at a tab's root does nothing`() {
        val lib = library()
        val before = lib.facade.state.value.queue

        lib.ops.playSelection()

        assertEquals(before, lib.facade.state.value.queue)
    }

    // --- the letter beside a dragged handle ---

    @Test
    fun `a row files under its first letter, and a digit under #`() {
        val lib = library(Shelf(listOf("autechre", "808 State", "élan", "...And You Will Know Us")))

        assertEquals("A", lib.ops.letterAt(0))
        assertEquals("#", lib.ops.letterAt(1))
        assertEquals("É", lib.ops.letterAt(2))
        assertEquals("A", lib.ops.letterAt(3))
        assertNull(lib.ops.letterAt(9))
    }

    @Test
    fun `a page of tracks has no letters, since it keeps an order of its own`() {
        val lib = library()
        lib.ops.tapRow(0)
        lib.ops.tapRow(0)

        assertNull(lib.ops.letterAt(0))
    }

    // --- a source that stopped part way ---

    @Test
    fun `a source that stopped part way says INCOMPLETE beside the count`() {
        val lib = library(whole = false)

        assertEquals("2 ARTISTS · INCOMPLETE", lib.ops.status)
    }

    @Test
    fun `pages the app keeps itself are never incomplete`() {
        val lib = library(whole = false)

        lib.ops.switchCategory(LibraryOps.Category.RADIO)

        assertEquals("0 STATIONS", lib.ops.status)
    }
}
