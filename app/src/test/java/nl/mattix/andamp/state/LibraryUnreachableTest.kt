// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.SourceUnreachable
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A library that could not answer, told apart from one that has nothing.
 *
 * For the pages a source can fail to fill: the failure names the source as
 * unreachable, by the name the listener picked it by, and asking again once
 * the source is back shows the real page. A source that is empty still says
 * NO LISTS.
 */
class LibraryUnreachableTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** One artist, one album, one list - or, while [down], nothing but [SourceUnreachable]. */
    private class Flaky(
        var down: Boolean = true,
        private val empty: Boolean = false,
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities(canSearch = true, hasPlaylists = true)
        override val available = true

        private fun <T> answer(rows: List<T>): List<T> {
            if (down) throw SourceUnreachable("the server is down")
            return if (empty) emptyList() else rows
        }

        override suspend fun artists() = answer(listOf(LibraryArtist("a1", "Autechre", albumCount = 1)))

        override suspend fun albums(artistId: String?) = answer(listOf(LibraryAlbum("l1", "Amber", "Autechre")))

        override suspend fun tracks(albumId: String) = answer(listOf(Track("t1", "Autechre", "Foil", 6_000, uri = "x:1")))

        override suspend fun search(
            query: String,
            limit: Int,
        ) = answer(listOf(Track("t1", "Autechre", "Foil", 6_000, uri = "x:1")))

        override suspend fun playlists() = answer(listOf(LibraryPlaylist("p1", "Driving")))

        override suspend fun playlistTracks(id: String) = tracks(id)
    }

    private fun TestScope.ops(
        source: BrowseSource,
        name: String = "Subsonic",
    ) = LibraryOps(
        { source },
        PlayerFacade(MockBackend(emptyList(), this)),
        WinampState(),
        this,
        PlaylistLibrary(temp.newFolder()),
        StationStore(File(temp.newFolder(), "stations.m3u")),
        nameOf = { name },
    )

    @Test
    fun `a library that could not be reached says so by name and shows no count`() =
        runTest {
            val ops = ops(Flaky())

            ops.open()
            runCurrent()

            assertEquals("CAN'T REACH SUBSONIC · TAP TO RETRY", ops.emptyNote)
            assertEquals("ARTISTS", ops.header)
            assertEquals("an unreachable library shows no count", "", ops.status)
            assertEquals(emptyList<LibraryOps.Row>(), ops.rows)
        }

    @Test
    fun `a tap on the unreachable page asks again and shows the real page`() =
        runTest {
            val source = Flaky()
            val ops = ops(source)
            ops.open()
            runCurrent()

            source.down = false
            ops.tapRow(0)
            runCurrent()

            assertEquals(listOf("Autechre"), ops.rows.map { it.label })
            assertEquals("1 ARTIST", ops.status)
        }

    /** On a page that was reached, the active tab at its root does nothing. */
    @Test
    fun `the active tab tapped on an unreachable page asks again`() =
        runTest {
            val source = Flaky()
            val ops = ops(source)
            ops.open()
            runCurrent()
            ops.switchCategory(LibraryOps.Category.LISTS)
            runCurrent()
            assertEquals("CAN'T REACH SUBSONIC · TAP TO RETRY", ops.emptyNote)

            source.down = false
            ops.switchCategory(LibraryOps.Category.LISTS)
            runCurrent()

            assertEquals(listOf("Driving"), ops.rows.map { it.label })
        }

    @Test
    fun `an empty library says NO LISTS`() =
        runTest {
            val ops = ops(Flaky(down = false, empty = true))
            ops.open()
            runCurrent()
            ops.switchCategory(LibraryOps.Category.LISTS)
            runCurrent()

            assertEquals("NO LISTS", ops.emptyNote)
            assertEquals("0 LISTS", ops.status)
        }

    /**
     * A drill that fails still goes down a level, headed by what was opened;
     * asking again replaces the failed page at the same depth.
     */
    @Test
    fun `an album that could not be opened asks again in place`() =
        runTest {
            val source = Flaky(down = false)
            val ops = ops(source)
            ops.open()
            runCurrent()
            ops.tapRow(0) // Autechre
            runCurrent()

            source.down = true
            ops.tapRow(0) // Amber
            runCurrent()
            assertEquals("Amber", ops.header)
            assertEquals(2, ops.depth)
            assertEquals("CAN'T REACH SUBSONIC · TAP TO RETRY", ops.emptyNote)

            source.down = false
            ops.tapRow(0)
            runCurrent()
            assertEquals(2, ops.depth)
            assertEquals(listOf("1. Foil"), ops.rows.map { it.label })

            ops.up()
            assertEquals("Autechre", ops.header)
        }

    @Test
    fun `a search that could not reach the source says so and a tap asks again`() =
        runTest {
            val source = Flaky()
            val down = ops(source)
            down.open()
            runCurrent()
            down.enterSearch()

            down.setQuery("foil", 4)
            advanceTimeBy(SEARCH_PAUSE_MS)
            runCurrent()
            assertEquals("CAN'T REACH SUBSONIC · TAP TO RETRY", down.emptyNote)

            source.down = false
            down.tapRow(0)
            advanceTimeBy(SEARCH_PAUSE_MS)
            runCurrent()
            assertEquals(listOf("Foil"), down.rows.map { it.label })
        }

    @Test
    fun `a row whose tracks could not be fetched flashes the reason`() =
        runTest {
            val source = Flaky(down = false)
            val ops = ops(source)
            ops.open()
            runCurrent()

            source.down = true
            ops.enqueueRow(0)
            runCurrent()

            assertEquals("CAN'T REACH SUBSONIC", ops.flash)
            assertEquals("the row stays in the list", listOf("Autechre"), ops.rows.map { it.label })
        }

    @Test
    fun `a library with no name is called the library`() =
        runTest {
            val ops = ops(Flaky(), name = "")
            ops.open()
            runCurrent()

            assertTrue(ops.emptyNote.startsWith("CAN'T REACH THE LIBRARY"))
        }

    private companion object {
        /** Longer than the search's 250 ms debounce. */
        const val SEARCH_PAUSE_MS = 300L
    }
}
