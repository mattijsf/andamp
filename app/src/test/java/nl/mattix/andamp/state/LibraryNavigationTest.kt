// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.ui.menu.libraryRowMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Moving about the library: the ALBUMS page's order, a track's way home, and the row for every track. */
class LibraryNavigationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val temp = TemporaryFolder()

    private val foil = Track("t1", "Autechre", "Foil", 6_000, uri = "content://1")
    private val dummy = Track("t2", "Portishead", "Mysterons", 7_000, uri = "content://2")

    private val autechre = LibraryArtist("a1", "Autechre", albumCount = 1)
    private val portishead = LibraryArtist("a2", "Portishead", albumCount = 1)
    private val amber = LibraryAlbum("l1", "Amber", "Autechre", year = 1994)
    private val dummyAlbum = LibraryAlbum("l2", "Dummy", "Portishead", year = 1994)
    private val zzz = LibraryAlbum("l3", "Accelerator", "Zebra", year = 1980)

    /** Two artists, three albums, and the way home of every track it has. */
    private inner class Shelf :
        BrowseSource,
        TrackHomes,
        EveryTrack {
        override val capabilities = BrowseCapabilities(canSearch = true)
        override val available = true

        override suspend fun artists() = listOf(autechre, portishead)

        override suspend fun albums(artistId: String?) =
            when (artistId) {
                null -> listOf(zzz, amber, dummyAlbum)
                "a1" -> listOf(amber)
                "a2" -> listOf(dummyAlbum)
                else -> emptyList()
            }

        override suspend fun tracks(albumId: String) =
            when (albumId) {
                "l1" -> listOf(foil)
                "l2" -> listOf(dummy)
                else -> emptyList()
            }

        override suspend fun search(
            query: String,
            limit: Int,
        ) = listOf(foil, dummy)

        override suspend fun albumOf(track: Track) = if (track.id == "t2") dummyAlbum else amber

        override suspend fun artistOf(track: Track) = if (track.id == "t2") portishead else autechre

        override suspend fun everyTrack() = listOf(foil, dummy)
    }

    private val state = WinampState()
    private val ordered = mutableListOf<AlbumOrder>()

    private fun library() =
        LibraryOps(
            { Shelf() },
            PlayerFacade(MockBackend(emptyList(), scope)),
            state,
            scope,
            PlaylistLibrary(temp.newFolder("playlists")),
            StationStore(File(temp.newFolder("radio"), "stations.m3u")),
            io = Dispatchers.Unconfined,
            words = EnglishWords,
            onOrder = { ordered += it },
        ).also { it.open() }

    @Test
    fun `the ALBUMS page goes by title, then by artist, then by year, and remembers the pick`() {
        val ops = library()
        ops.switchCategory(LibraryOps.Category.ALBUMS)
        assertTrue(ops.canOrder)
        assertEquals(listOf("Accelerator", "Amber", "Dummy"), ops.rows.map { it.label })

        ops.nextOrder()
        assertEquals(listOf("Amber", "Dummy", "Accelerator"), ops.rows.map { it.label })
        assertEquals("the readout follows the artists", "A", ops.letterAt(0))

        ops.nextOrder()
        assertEquals(listOf("Accelerator", "Amber", "Dummy"), ops.rows.map { it.label })
        assertEquals("1980", ops.rows.first().detail)
        assertEquals("1980", ops.letterAt(0))

        assertEquals(listOf(AlbumOrder.ARTIST, AlbumOrder.YEAR), ordered)
    }

    @Test
    fun `only the ALBUMS tab's own page has an order`() {
        val ops = library()
        assertFalse(ops.canOrder)
        ops.switchCategory(LibraryOps.Category.ALBUMS)
        ops.tapRow(1)
        assertFalse(ops.canOrder)
    }

    @Test
    fun `a track found by search goes to its album and to its artist`() =
        runTest {
            val ops =
                LibraryOps(
                    { Shelf() },
                    PlayerFacade(MockBackend(emptyList(), scope)),
                    state,
                    this,
                    PlaylistLibrary(temp.newFolder("playlists")),
                    StationStore(File(temp.newFolder("radio"), "stations.m3u")),
                    io = StandardTestDispatcher(testScheduler),
                    words = EnglishWords,
                )
            ops.open()
            advanceUntilIdle()
            ops.enterSearch()
            ops.setQuery("oi", 2)
            advanceUntilIdle()
            assertTrue(ops.canGoHome(0))
            val labels =
                libraryRowMenu(ops, 0, ops.rows[0].label, MenuAnchor("library", 0, 0, 0, 0), EnglishWords)
                    .items
                    .filterIsInstance<AmpMenuItem.Action>()
                    .map { it.label }
            assertTrue(labels.containsAll(listOf("Go to album", "Go to artist")))

            ops.goToAlbum(1) // Mysterons
            advanceUntilIdle()
            assertEquals("Dummy", ops.header)
            assertEquals(listOf("1. Mysterons"), ops.rows.map { it.label })

            ops.up()
            ops.goToArtist(0) // Foil
            advanceUntilIdle()
            assertEquals("Autechre", ops.header)
            assertEquals(listOf("Amber"), ops.rows.map { it.label })
        }

    @Test
    fun `ARTISTS starts with a row for every track, which opens them all by artist and title`() {
        val ops = library()

        assertEquals("ALL (2 ARTISTS)", ops.rows.first().label)
        assertNull(ops.keptAt(0))

        ops.tapRow(0)
        assertEquals("ALL", ops.header)
        assertEquals(listOf("1. Autechre - Foil", "2. Portishead - Mysterons"), ops.rows.map { it.label })
    }
}
