// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
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
 * The FIND tab, which searches the source's catalogue for artists.
 *
 * The tab opens on its search field, the answers are artists, a found artist
 * opens the way one on the ARTISTS tab does, and going back up from one
 * returns to the query that found it.
 */
class LibraryFindTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A source with a library of one artist and a catalogue holding others. */
    private class Catalogue : BrowseSource {
        override val capabilities = BrowseCapabilities(canSearch = true, hasCatalogue = true)
        override val available = true

        override suspend fun artists() = listOf(LibraryArtist("mine", "Autechre"))

        override suspend fun albums(artistId: String?) =
            when (artistId) {
                "remote:artist:boc" -> {
                    listOf(
                        LibraryAlbum("remote:album:geogaddi", "Geogaddi", "Boards of Canada", 2002),
                        LibraryAlbum("remote:album:peel", "Peel Session", "Boards of Canada", 1999, kind = AlbumKind.SINGLE),
                    )
                }

                "remote:artist:one" -> {
                    listOf(LibraryAlbum("remote:album:only", "Only", "One Kind", 2000))
                }

                "remote:artist:seefeel" -> {
                    listOf(
                        LibraryAlbum("remote:album:plainsong", "Plainsong", "Seefeel", 1993, kind = AlbumKind.SINGLE),
                        LibraryAlbum("remote:album:time", "Time To Find Me", "Seefeel", 1993, kind = AlbumKind.SINGLE),
                    )
                }

                else -> {
                    emptyList()
                }
            }

        override suspend fun tracks(albumId: String) =
            when (albumId) {
                "remote:album:geogaddi" -> {
                    listOf(Track("t1", "Boards of Canada", "Music Is Math", 300_000, uri = "remote:track:t1"))
                }

                "remote:album:peel" -> {
                    listOf(Track("t2", "Boards of Canada", "Aquarius", 200_000, uri = "remote:track:t2"))
                }

                else -> {
                    emptyList()
                }
            }

        override suspend fun findArtists(
            query: String,
            limit: Int,
        ) = when {
            query.lowercase() in "boards of canada" -> listOf(LibraryArtist("remote:artist:boc", "Boards of Canada"))
            query.lowercase() in "one kind" -> listOf(LibraryArtist("remote:artist:one", "One Kind"))
            query.lowercase() in "seefeel" -> listOf(LibraryArtist("remote:artist:seefeel", "Seefeel"))
            else -> emptyList()
        }
    }

    /** An artist whose records are of more than one [AlbumKind] opens on a row per kind. */
    @Test
    fun `an artist with albums and singles shows the kinds first`() =
        runTest {
            val ops = ops()
            ops.open()
            runCurrent()
            ops.switchCategory(LibraryOps.Category.FIND)
            runCurrent()
            ops.setQuery("boards", 6)
            advanceTimeBy(400)
            runCurrent()

            ops.tapRow(0)
            runCurrent()

            assertEquals(listOf("ALBUMS", "SINGLES"), ops.rows.map { it.label })
        }

    @Test
    fun `opening a kind shows that kind's records`() =
        runTest {
            val ops = ops()
            ops.open()
            runCurrent()
            ops.switchCategory(LibraryOps.Category.FIND)
            runCurrent()
            ops.setQuery("boards", 6)
            advanceTimeBy(400)
            runCurrent()
            ops.tapRow(0)
            runCurrent()

            ops.tapRow(1)
            runCurrent()

            assertEquals(listOf("Peel Session"), ops.rows.map { it.label })
        }

    /** The row tapped to get here says SINGLES, so the status counts singles. */
    @Test
    fun `a kind's page counts its records as that kind`() =
        runTest {
            val ops = asked("boards")
            ops.tapRow(0)
            runCurrent()

            ops.tapRow(1)
            runCurrent()

            assertEquals("1 SINGLE", ops.status)
        }

    @Test
    fun `an artist with one kind of record opens straight onto the records`() =
        runTest {
            val ops = ops()
            ops.open()
            runCurrent()
            ops.switchCategory(LibraryOps.Category.FIND)
            runCurrent()
            ops.setQuery("one kind", 8)
            advanceTimeBy(400)
            runCurrent()

            ops.tapRow(0)
            runCurrent()

            assertEquals(listOf("Only"), ops.rows.map { it.label })
        }

    @Test
    fun `records of a single kind are counted as that kind`() =
        runTest {
            val ops = asked("seefeel")

            ops.tapRow(0)
            runCurrent()

            assertEquals(listOf("Plainsong", "Time To Find Me"), ops.rows.map { it.label })
            assertEquals("2 SINGLES", ops.status)
        }

    private fun kotlinx.coroutines.test.TestScope.ops(): LibraryOps {
        val facade = PlayerFacade(MockBackend(emptyList(), this))
        return LibraryOps(
            { Catalogue() },
            facade,
            WinampState(),
            this,
            PlaylistLibrary(temp.newFolder()),
            StationStore(File(temp.newFolder(), "stations.m3u")),
        )
    }

    /** The find tab with [query] typed into it, once the answer has landed. */
    private fun kotlinx.coroutines.test.TestScope.asked(query: String): LibraryOps {
        val ops = ops()
        ops.open()
        runCurrent()
        ops.switchCategory(LibraryOps.Category.FIND)
        runCurrent()
        ops.setQuery(query, query.length)
        advanceTimeBy(400)
        runCurrent()
        return ops
    }

    @Test
    fun `a source with a catalogue shows the find tab`() =
        runTest {
            val ops = ops()

            assertTrue(ops.visibleCategories.contains(LibraryOps.Category.FIND))
        }

    @Test
    fun `the find tab opens on its search prompt`() =
        runTest {
            val ops = ops()
            ops.open()
            runCurrent()

            ops.switchCategory(LibraryOps.Category.FIND)
            runCurrent()

            assertTrue("the search field is active", ops.searching)
            assertEquals("TYPE AN ARTIST", ops.emptyNote)
        }

    @Test
    fun `clearing the box asks for an artist again`() =
        runTest {
            val ops = asked("boards")

            ops.searchClear()
            runCurrent()

            assertEquals("TYPE AN ARTIST", ops.emptyNote)
            assertEquals("FIND", ops.header)
        }

    @Test
    fun `a query too short to ask shows the artist prompt`() =
        runTest {
            val ops = asked("boards")

            ops.setQuery("b", 1)
            advanceTimeBy(400)
            runCurrent()

            assertEquals("TYPE AN ARTIST", ops.emptyNote)
            assertEquals("FIND", ops.header)
        }

    @Test
    fun `a find query returns artists`() =
        runTest {
            val ops = ops()
            ops.open()
            runCurrent()
            ops.switchCategory(LibraryOps.Category.FIND)
            runCurrent()

            ops.setQuery("boards", 6)
            advanceTimeBy(400)
            runCurrent()

            assertEquals(listOf("Boards of Canada"), ops.rows.map { it.label })
        }

    @Test
    fun `a found artist opens into kinds, records and tracks`() =
        runTest {
            val ops = ops()
            ops.open()
            runCurrent()
            ops.switchCategory(LibraryOps.Category.FIND)
            runCurrent()
            ops.setQuery("boards", 6)
            advanceTimeBy(400)
            runCurrent()

            ops.tapRow(0)
            runCurrent()
            assertEquals(listOf("ALBUMS", "SINGLES"), ops.rows.map { it.label })

            ops.tapRow(0)
            runCurrent()
            assertEquals(listOf("Geogaddi"), ops.rows.map { it.label })

            ops.tapRow(0)
            runCurrent()
            assertEquals(listOf("1. Music Is Math"), ops.rows.map { it.label })
        }

    /** Opening an artist leaves the search, so the header is the way back up and typing changes nothing. */
    @Test
    fun `an opened artist is a page the next keystroke cannot replace`() =
        runTest {
            val ops = asked("boards")
            ops.tapRow(0)
            runCurrent()
            assertFalse("an opened artist leaves the search field", ops.searching)

            ops.setQuery("one kind", 8)
            advanceTimeBy(400)
            runCurrent()

            assertEquals("Boards of Canada", ops.header)
            assertEquals(listOf("ALBUMS", "SINGLES"), ops.rows.map { it.label })
        }

    @Test
    fun `coming back from a found artist brings back the question that found them`() =
        runTest {
            val ops = asked("boards")
            ops.tapRow(0)
            runCurrent()

            ops.up()
            runCurrent()

            assertTrue("the search field is active again", ops.searching)
            assertEquals("boards", ops.query)
            assertEquals(0, ops.depth)
            assertEquals("FIND", ops.header)
            assertEquals(listOf("Boards of Canada"), ops.rows.map { it.label })
        }

    @Test
    fun `a query typed after coming back still finds artists`() =
        runTest {
            val ops = asked("boards")
            ops.tapRow(0)
            runCurrent()
            ops.back()
            runCurrent()

            ops.setQuery("one kind", 8)
            advanceTimeBy(400)
            runCurrent()

            assertEquals("FIND", ops.header)
            assertEquals(listOf("One Kind"), ops.rows.map { it.label })
        }

    @Test
    fun `the tab tapped after coming back opens a fresh question`() =
        runTest {
            val ops = asked("boards")
            ops.tapRow(0)
            runCurrent()
            ops.up()
            runCurrent()

            ops.switchCategory(LibraryOps.Category.FIND)
            runCurrent()

            assertTrue("the search field is active", ops.searching)
            assertEquals("", ops.query)
            assertEquals("TYPE AN ARTIST", ops.emptyNote)

            ops.setQuery("one kind", 8)
            advanceTimeBy(400)
            runCurrent()
            assertEquals(listOf("One Kind"), ops.rows.map { it.label })
        }

    @Test
    fun `the artists tab lists only the listener's library`() =
        runTest {
            val ops = ops()
            ops.open()
            runCurrent()

            ops.switchCategory(LibraryOps.Category.ARTISTS)
            runCurrent()

            assertEquals(listOf("Autechre"), ops.rows.map { it.label })
        }
}
