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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The library's drill-down, against a hand-rolled shelf of two artists.
 *
 * A tap on a track selects it; a second tap, or PLAY, replaces the queue and
 * starts playback from that track. Enqueue appends through
 * [PlayerFacade.enqueue] and leaves the current index where it is.
 */
class LibraryOpsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val temp = TemporaryFolder()

    /** Two artists, one album each, two songs on the first album. */
    private class Shelf(
        override val available: Boolean = true,
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities(canSearch = true)

        override suspend fun artists() =
            listOf(
                LibraryArtist("a1", "Autechre", albumCount = 1),
                LibraryArtist("a2", "Boards of Canada", albumCount = 1),
            )

        override suspend fun albums(artistId: String?) =
            when (artistId) {
                "a1" -> listOf(LibraryAlbum("l1", "Amber", "Autechre", year = 1994))

                "a2" -> listOf(LibraryAlbum("l2", "Geogaddi", "Boards of Canada", year = 2002))

                // null is the all-albums root
                null -> listOf(LibraryAlbum("l1", "Amber", "Autechre", year = 1994))

                else -> emptyList()
            }

        override suspend fun tracks(albumId: String) =
            when (albumId) {
                "l1" -> {
                    listOf(
                        Track("t1", "Autechre", "Foil", 6_000, uri = "content://1"),
                        Track("t2", "Autechre", "Montreal", 7_000, uri = "content://2"),
                    )
                }

                else -> {
                    emptyList()
                }
            }
    }

    private fun player(source: BrowseSource = Shelf()): Triple<LibraryOps, WinampState, PlayerFacade> {
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        val ops = LibraryOps({ source }, facade, state, scope, lists(), stations())
        return Triple(ops, state, facade)
    }

    private fun lists() = PlaylistLibrary(temp.newFolder("playlists"))

    private fun stations() = StationStore(java.io.File(temp.newFolder("radio"), "stations.m3u"))

    private fun LibraryOps.drillToTracks() {
        tapRow(0) // Autechre
        tapRow(0) // Amber
    }

    @Test
    fun `opening shows the artists with their album counts`() {
        val (ops, state, _) = player()

        ops.open()

        assertTrue(state.libraryOpen)
        assertEquals(listOf("Autechre", "Boards of Canada"), ops.rows.map { it.label })
        assertEquals(listOf("1 ALBUM", "1 ALBUM"), ops.rows.map { it.detail })
        assertEquals("ARTISTS", ops.header)
        assertEquals("2 ARTISTS", ops.status)
        assertEquals(0, ops.depth)
    }

    @Test
    fun `an artist opens into albums and up comes back`() {
        val (ops, _, _) = player()
        ops.open()

        ops.tapRow(0)

        assertEquals(listOf("Amber"), ops.rows.map { it.label })
        assertEquals("Autechre", ops.header)
        assertEquals("1 ALBUM", ops.status)
        assertEquals(1, ops.depth)

        ops.up()
        assertEquals(listOf("Autechre", "Boards of Canada"), ops.rows.map { it.label })
        assertEquals(0, ops.depth)
    }

    @Test
    fun `a track tap selects and the second tap plays the album from there`() {
        val (ops, state, facade) = player()
        ops.open()
        ops.drillToTracks()
        assertEquals("2 TRACKS · 0:13", ops.status)

        ops.tapRow(1)
        assertEquals(1, state.librarySelected)
        assertEquals(Transport.Stopped, facade.state.value.transport)

        ops.tapRow(1)
        assertEquals(
            listOf("Foil", "Montreal"),
            facade.state.value.queue
                .map { it.title },
        )
        assertEquals(1, facade.state.value.currentIndex)
        assertEquals(Transport.Playing, facade.state.value.transport)
    }

    @Test
    fun `a tap on the empty area under the rows clears the selection`() {
        val (ops, state, _) = player()
        ops.open()
        ops.drillToTracks()
        ops.tapRow(1)
        assertEquals(1, state.librarySelected)

        ops.tapRow(7) // past the two tracks: the void below the list

        assertEquals(-1, state.librarySelected)
    }

    @Test
    fun `play without a selection starts at the top`() {
        val (ops, _, facade) = player()
        ops.open()
        ops.drillToTracks()

        ops.playSelection()

        assertEquals(0, facade.state.value.currentIndex)
        assertEquals(Transport.Playing, facade.state.value.transport)
    }

    @Test
    fun `enqueue appends the album and leaves the cursor alone`() {
        val (ops, _, facade) = player()
        val before = facade.state.value.queue.size
        ops.open()
        ops.drillToTracks()

        ops.enqueueSelection() // nothing selected: the whole album

        assertEquals(before + 2, facade.state.value.queue.size)
        assertEquals(
            "Montreal",
            facade.state.value.queue
                .last()
                .title,
        )
        assertEquals(0, facade.state.value.currentIndex)
        assertEquals(Transport.Stopped, facade.state.value.transport)
        assertEquals("+2 QUEUED", ops.flash)
    }

    @Test
    fun `enqueue with a selection appends just that track`() {
        val (ops, _, facade) = player()
        val before = facade.state.value.queue.size
        ops.open()
        ops.drillToTracks()

        ops.tapRow(0)
        ops.enqueueSelection()

        assertEquals(before + 1, facade.state.value.queue.size)
        assertEquals("+1 QUEUED", ops.flash)
    }

    @Test
    fun `up restores the scroll and selection it left`() {
        val (ops, state, _) = player()
        ops.open()
        state.libraryScroll = 1
        state.librarySelected = 1

        ops.tapRow(1) // drill into Boards of Canada
        assertEquals(0, state.libraryScroll)
        assertEquals(-1, state.librarySelected)

        ops.up()
        assertEquals(1, state.libraryScroll)
        assertEquals(1, state.librarySelected)
    }

    @Test
    fun `back goes up a level and only then closes the window`() {
        val (ops, state, _) = player()
        ops.open()
        ops.tapRow(0)

        ops.back()
        assertTrue(state.libraryOpen)
        assertEquals(0, ops.depth)

        ops.back()
        assertFalse(state.libraryOpen)
    }

    @Test
    fun `every landed page bumps the revision`() {
        val (ops, _, _) = player()
        ops.open()
        val atRoot = ops.revision

        ops.tapRow(0)
        assertTrue(ops.revision > atRoot)

        ops.up()
        assertTrue(ops.revision > atRoot + 1)
    }

    @Test
    fun `an unreadable library asks for access`() {
        val (ops, _, _) = player(Shelf(available = false))

        ops.open()

        assertTrue(ops.needsAccess)
        assertTrue(ops.rows.isEmpty())
        assertEquals("0 ARTISTS", ops.status)
    }

    // --- categories ---

    @Test
    fun `the albums tab lists every album with its artist`() {
        val (ops, _, _) = player()
        ops.open()

        ops.switchCategory(LibraryOps.Category.ALBUMS)

        assertEquals(LibraryOps.Category.ALBUMS, ops.category)
        assertEquals("ALBUMS", ops.header)
        assertEquals(listOf("Amber"), ops.rows.map { it.label })
        assertEquals(listOf("Autechre"), ops.rows.map { it.detail })
        assertEquals(0, ops.depth)
    }

    @Test
    fun `a tab switch from deep inside lands at the new root`() {
        val (ops, _, _) = player()
        ops.open()
        ops.drillToTracks()
        assertEquals(2, ops.depth)

        ops.switchCategory(LibraryOps.Category.ALBUMS)

        assertEquals(0, ops.depth)
        assertEquals("ALBUMS", ops.header)
    }

    @Test
    fun `the active tab tapped while deep pops back to its root`() {
        val (ops, _, _) = player()
        ops.open()
        ops.drillToTracks()

        ops.switchCategory(LibraryOps.Category.ARTISTS)

        assertEquals(0, ops.depth)
        assertEquals("ARTISTS", ops.header)
    }

    /** A source that gains the artists shelf while the app runs. */
    private class GrowingShelf : BrowseSource {
        var hasArtists = false
        override val capabilities: BrowseCapabilities
            get() = BrowseCapabilities(hasArtists = hasArtists, hasAlbums = true, canSearch = true)
        override val available = true

        override suspend fun artists() = emptyList<LibraryArtist>()

        override suspend fun albums(artistId: String?) = emptyList<LibraryAlbum>()

        override suspend fun tracks(albumId: String) = emptyList<Track>()
    }

    @Test
    fun `a shelf the source gains while running reaches the strip`() {
        val source = GrowingShelf()
        val ops =
            LibraryOps(
                { source },
                PlayerFacade(MockBackend(FakeTracks.tracks, scope)),
                WinampState(),
                scope,
                PlaylistLibrary(temp.newFolder("lists")),
                StationStore(java.io.File(temp.newFolder("radio"), "stations.m3u")),
            )
        assertEquals(
            listOf(LibraryOps.Category.ALBUMS, LibraryOps.Category.LISTS, LibraryOps.Category.RADIO),
            ops.visibleCategories,
        )

        source.hasArtists = true

        assertEquals(
            listOf(
                LibraryOps.Category.ARTISTS,
                LibraryOps.Category.ALBUMS,
                LibraryOps.Category.LISTS,
                LibraryOps.Category.RADIO,
            ),
            ops.visibleCategories,
        )
    }

    @Test
    fun `a source without a catalogue shows every tab but find`() {
        val (ops, _, _) = player()
        assertEquals(
            listOf(
                LibraryOps.Category.ARTISTS,
                LibraryOps.Category.ALBUMS,
                LibraryOps.Category.LISTS,
                LibraryOps.Category.RADIO,
            ),
            ops.visibleCategories,
        )
    }

    // --- saved lists ---

    @Test
    fun `a saved list is browsable and playable from the lists tab`() {
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        val shelf = lists()
        shelf.save(
            "Road trip",
            listOf(
                Track("r1", "A", "One", 60_000, uri = "content://1"),
                Track("r2", "B", "Two", 120_000, uri = "content://2"),
            ),
        )
        val ops = LibraryOps({ Shelf() }, facade, state, scope, shelf, stations())
        ops.open()

        ops.switchCategory(LibraryOps.Category.LISTS)
        assertEquals(listOf("Road trip"), ops.rows.map { it.label })
        assertEquals(listOf("2 TRK"), ops.rows.map { it.detail })

        ops.tapRow(0)
        assertEquals("Road trip", ops.header)
        assertEquals(listOf("1. One", "2. Two"), ops.rows.map { it.label })

        ops.tapRow(1)
        ops.tapRow(1)
        assertEquals(Transport.Playing, facade.state.value.transport)
        assertEquals(1, facade.state.value.currentIndex)
    }

    @Test
    fun `an empty lists tab says how to fill it`() {
        val (ops, _, _) = player()
        ops.open()

        ops.switchCategory(LibraryOps.Category.LISTS)

        assertTrue(ops.rows.isEmpty())
        assertEquals("NO SAVED LISTS · LIST > SAVE KEEPS ONE", ops.emptyNote)
    }

    // --- radio ---

    @Test
    fun `stations are a playable page and playing one queues them all`() {
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        val dial = stations()
        dial.add("Example FM", "http://radio.example.com/stream")
        dial.add("Radio Example", "https://example.com/fm")
        val ops = LibraryOps({ Shelf() }, facade, state, scope, lists(), dial)
        ops.open()

        ops.switchCategory(LibraryOps.Category.RADIO)
        assertTrue(ops.atTracks)
        assertEquals(listOf("Example FM", "Radio Example"), ops.rows.map { it.label })

        ops.tapRow(1)
        ops.tapRow(1)
        assertEquals(Transport.Playing, facade.state.value.transport)
        assertEquals(1, facade.state.value.currentIndex)
        assertEquals(
            listOf("Example FM", "Radio Example"),
            facade.state.value.queue
                .map { it.title },
        )
    }

    @Test
    fun `a new station asks for the url and then a name suggested from its host`() {
        val (ops, state, _) = player()
        ops.open()
        ops.switchCategory(LibraryOps.Category.RADIO)

        ops.promptNewStation()
        val urlPrompt = state.namePrompt
        assertEquals("Station URL", urlPrompt?.title)

        urlPrompt?.onSubmit?.invoke("http://radio.example.com/stream")
        val namePrompt = state.namePrompt
        assertEquals("Station name", namePrompt?.title)
        assertEquals("radio.example.com", namePrompt?.initial)

        namePrompt?.onSubmit?.invoke("Late Night Example")
        assertEquals(listOf("Late Night Example"), ops.rows.map { it.label })
    }

    @Test
    fun `a station url that is not a stream is refused with a reason`() {
        val (ops, state, _) = player()
        ops.open()
        ops.switchCategory(LibraryOps.Category.RADIO)

        ops.promptNewStation()
        state.namePrompt?.onSubmit?.invoke("not a url")

        assertEquals("NOT AN HTTP URL", ops.flash)
        assertEquals(null, state.namePrompt)
        assertTrue(ops.rows.isEmpty())
    }

    // --- search ---

    @Test
    fun `the header opens the search at the root and climbs while deep`() {
        val (ops, _, _) = player()
        ops.open()
        ops.tapRow(0)
        assertEquals(1, ops.depth)

        ops.headerTap()
        assertEquals(0, ops.depth)
        assertFalse(ops.searching)

        ops.headerTap()
        assertTrue(ops.searching)
        assertEquals("SEARCH", ops.header)
        assertEquals("TYPE TO SEARCH", ops.emptyNote)
    }

    @Test
    fun `leaving the search restores the page it covered`() {
        val (ops, state, _) = player()
        ops.open()
        state.libraryScroll = 1
        state.librarySelected = 1

        ops.enterSearch()
        assertEquals(0, state.libraryScroll)

        ops.searchClear() // empty query: the x closes the search
        assertFalse(ops.searching)
        assertEquals("ARTISTS", ops.header)
        assertEquals(1, state.libraryScroll)
        assertEquals(1, state.librarySelected)
    }

    @Test
    fun `a tab switch clears the search`() {
        val (ops, _, _) = player()
        ops.open()
        ops.enterSearch()

        ops.switchCategory(LibraryOps.Category.ALBUMS)

        assertFalse(ops.searching)
        assertEquals("", ops.query)
        assertEquals("ALBUMS", ops.header)
    }

    @Test
    fun `the playing track's row is found by id`() {
        val (ops, _, facade) = player()
        ops.open()
        ops.drillToTracks()
        ops.tapRow(1)
        ops.tapRow(1) // playing Montreal

        assertEquals(
            1,
            ops.rowOfTrack(
                facade.state.value.currentTrack
                    ?.id,
            ),
        )
        assertEquals(-1, ops.rowOfTrack("elsewhere"))
    }
}
