// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import java.io.File

/**
 * A search in a library that finds artists and albums too ([SectionedSearch]): one page in
 * sections, and a library that is read again while it is shown ([ShelfUpdates]).
 */
class LibrarySectionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val temp = TemporaryFolder()

    private val foil = Track("t1", "Autechre", "Foil", 6_000, uri = "content://1")
    private val montreal = Track("t2", "Autechre", "Montreal", 7_000, uri = "content://2")

    /** One artist with one album; a search finds the artist, the album and both tracks. */
    private inner class Shelf :
        BrowseSource,
        SectionedSearch,
        FoldersAndGenres {
        var artists = listOf(LibraryArtist("a1", "Autechre", albumCount = 1))
        var genres = emptyList<Pair<String, Int?>>()
        override val capabilities = BrowseCapabilities(canSearch = true)
        override val available = true

        override suspend fun artists() = artists

        override suspend fun albums(artistId: String?) = listOf(LibraryAlbum("l1", "Amber", "Autechre"))

        override suspend fun tracks(albumId: String) = if (albumId == "l1") listOf(foil, montreal) else emptyList()

        override suspend fun searchSections(
            query: String,
            limit: Int,
        ) = SectionedSearch.Sections(artists, albums(null), listOf(foil, montreal))

        override suspend fun folders() = emptyList<Pair<String, Int?>>()

        override suspend fun folderTracks(folder: String) = emptyList<Track>()

        override suspend fun genres() = genres

        override suspend fun genreTracks(genre: String) = listOf(foil)
    }

    private val shelf = Shelf()
    private val state = WinampState()
    private val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))

    /** The library on the test's clock; the player it plays on runs apart from it. */
    private fun TestScope.library() =
        LibraryOps(
            { shelf },
            facade,
            state,
            this,
            PlaylistLibrary(temp.newFolder("playlists")),
            StationStore(File(temp.newFolder("radio"), "stations.m3u")),
            io = StandardTestDispatcher(testScheduler),
            words = EnglishWords,
        ).also {
            it.open()
            advanceUntilIdle()
        }

    /** Types [text] into the search and lets the pause after the last key pass. */
    private fun TestScope.search(
        ops: LibraryOps,
        text: String,
    ) {
        ops.enterSearch()
        ops.setQuery(text, text.length)
        advanceUntilIdle()
    }

    @Test
    fun `a search lists artists, albums and tracks, each under a heading with its count`() =
        runTest {
            val ops = library()

            search(ops, "aut")

            assertEquals(
                listOf("1 ARTIST", "Autechre", "1 ALBUM", "Amber", "2 TRACKS", "Autechre - Foil", "Autechre - Montreal"),
                ops.rows.map { it.label },
            )
            assertEquals(listOf(0, 2, 4), ops.rows.indices.filter(ops::headingAt))
            assertEquals("4 HITS", ops.status)
        }

    @Test
    fun `a heading does nothing, an artist opens and a track plays from where it is`() =
        runTest {
            val ops = library()
            search(ops, "aut")

            ops.tapRow(0)
            assertEquals("SEARCH", ops.header)

            ops.tapRow(6) // Montreal
            ops.tapRow(6)
            assertEquals(Transport.Playing, facade.state.value.transport)
            assertEquals(
                listOf("Foil", "Montreal"),
                facade.state.value.queue
                    .map { it.title },
            )
            assertEquals(1, facade.state.value.currentIndex)

            ops.tapRow(1) // Autechre
            advanceUntilIdle()
            assertEquals("Autechre", ops.header)
            ops.up()
            assertTrue("going up comes back to the search", ops.searching)
        }

    @Test
    fun `the playing track is found among the tracks, below the other sections`() =
        runTest {
            val ops = library()
            search(ops, "aut")

            assertEquals(6, ops.rowOfTrack("t2"))
            assertEquals(-1, ops.rowOfTrack("nothing"))
        }

    @Test
    fun `PLAY plays the tracks the search found`() =
        runTest {
            val ops = library()
            search(ops, "aut")

            ops.playSelection()

            assertEquals(
                listOf("Foil", "Montreal"),
                facade.state.value.queue
                    .map { it.title },
            )
            assertEquals(0, facade.state.value.currentIndex)
        }

    @Test
    fun `a library read again while its tab's root is on screen shows what it has now, at the same scroll`() =
        runTest {
            val ops = library()
            state.libraryScroll = 0
            shelf.artists = shelf.artists + LibraryArtist("a2", "Boards of Canada", albumCount = 1)

            ops.shelfChanged()
            advanceUntilIdle()

            assertEquals(listOf("Autechre", "Boards of Canada"), ops.rows.map { it.label })
        }

    @Test
    fun `a library read again leaves a level deeper in alone`() =
        runTest {
            val ops = library()
            ops.tapRow(0) // Autechre
            advanceUntilIdle()
            shelf.artists = emptyList()

            ops.shelfChanged()
            advanceUntilIdle()

            assertEquals("Autechre", ops.header)
            assertFalse(ops.loading)
        }

    @Test
    fun `genres that arrive while the lists are on screen are shown`() =
        runTest {
            val ops = library()
            ops.switchCategory(LibraryOps.Category.LISTS)
            advanceUntilIdle()
            val before = ops.rows.map { it.label }
            shelf.genres = listOf("IDM" to 2)

            ops.shelfChanged()
            advanceUntilIdle()

            assertEquals(listOf("GENRES") + before, ops.rows.map { it.label })
        }
}
