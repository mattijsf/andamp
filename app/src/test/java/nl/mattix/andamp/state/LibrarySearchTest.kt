// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The search's timing, on the virtual clock: keystrokes coalesce into one
 * query after the pause, a query of one letter is not sent to the source, and an answer to
 * an old query cannot clobber a newer one.
 */
class LibrarySearchTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class SearchShelf(
        private val delayMs: Long = 0,
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities(canSearch = true)
        override val available = true
        var calls = 0

        val all =
            listOf(
                Track("s1", "Nirvana", "Lithium", 60_000, uri = "content://1"),
                Track("s2", "Nirvana", "Come as You Are", 60_000, uri = "content://2"),
                Track("s3", "Autechre", "Foil", 60_000, uri = "content://3"),
            )

        override suspend fun artists() = emptyList<LibraryArtist>()

        override suspend fun albums(artistId: String?) = emptyList<LibraryAlbum>()

        override suspend fun tracks(albumId: String) = emptyList<Track>()

        override suspend fun search(
            query: String,
            limit: Int,
        ): List<Track> {
            calls++
            if (delayMs > 0) delay(delayMs)
            return all.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.ops(shelf: SearchShelf): LibraryOps {
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(emptyList(), this))
        return LibraryOps(
            { shelf },
            facade,
            state,
            this,
            PlaylistLibrary(temp.newFolder()),
            StationStore(File(temp.newFolder(), "stations.m3u")),
        )
    }

    @Test
    fun `keystrokes coalesce into one search after the pause`() =
        runTest {
            val shelf = SearchShelf()
            val ops = ops(shelf)
            ops.open()
            runCurrent()
            ops.enterSearch()

            ops.setQuery("n", 1)
            ops.setQuery("ni", 2)
            ops.setQuery("nirv", 4)
            advanceTimeBy(251)
            runCurrent()

            assertEquals(1, shelf.calls)
            assertEquals(listOf("Lithium", "Come as You Are"), ops.rows.map { it.label })
            assertEquals(listOf("Nirvana", "Nirvana"), ops.rows.map { it.detail })
            assertEquals("2 HITS · 2:00", ops.status)
        }

    @Test
    fun `a single letter is not sent to the source`() =
        runTest {
            val shelf = SearchShelf()
            val ops = ops(shelf)
            ops.open()
            runCurrent()
            ops.enterSearch()

            ops.setQuery("n", 1)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(0, shelf.calls)
            assertEquals("TYPE TO SEARCH", ops.emptyNote)
        }

    @Test
    fun `an answer to an older query does not replace a newer one`() =
        runTest {
            val shelf = SearchShelf(delayMs = 500)
            val ops = ops(shelf)
            ops.open()
            runCurrent()
            ops.enterSearch()

            ops.setQuery("autechre", 8)
            advanceTimeBy(300) // past the debounce; the slow fetch is in flight
            ops.setQuery("nirvana", 7)
            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(listOf("Lithium", "Come as You Are"), ops.rows.map { it.label })
        }
}
