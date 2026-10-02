// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavioral contract every [BrowseSource] should pass: subclass per source and build
 * [createSource] from the [Shelf] it is handed.
 *
 * It fixes the answers that two sources could otherwise give differently: `albums(null)` is
 * every album, an album's tracks come back in track order, and a source that cannot search
 * answers empty.
 *
 * A source that cannot meet a case overrides that case and says why; it does not skip the
 * suite.
 */
abstract class BrowseSourceContractTest {
    /**
     * What the source under test should be able to see. A source builds whatever backing it
     * needs from this (rows in a fake resolver, a stubbed web response) and answers the
     * suite's questions from it.
     */
    data class Shelf(
        val artists: List<ShelfArtist>,
        /** Named lists the source keeps, for the sources that keep any. */
        val playlists: List<ShelfPlaylist> = emptyList(),
        /** Artists the source has but the listener does not: the catalog behind the library. */
        val catalogue: List<ShelfArtist> = emptyList(),
    )

    /** A named list, and the titles it holds in the order it holds them. */
    data class ShelfPlaylist(
        val name: String,
        val titles: List<String>,
    )

    data class ShelfArtist(
        val name: String,
        val albums: List<ShelfAlbum>,
    )

    data class ShelfAlbum(
        val title: String,
        val year: Int?,
        /** Title to track number; a null number is a track without one. */
        val tracks: List<Pair<String, Int?>>,
    )

    protected abstract fun createSource(shelf: Shelf): BrowseSource

    /**
     * The same source over a far end that cannot be reached (a server that is down, a
     * network that is gone, an account refused), or null for a source that has no far end.
     *
     * A source that overrides this is held to throwing [SourceUnreachable] where it would
     * otherwise answer empty.
     */
    protected open fun createUnreachableSource(shelf: Shelf): BrowseSource? = null

    /**
     * Two library artists, one with an album whose tracks are listed out of order, two
     * playlists and one catalog artist.
     */
    protected fun shelf() =
        Shelf(
            catalogue =
                listOf(
                    ShelfArtist(
                        "Boards of Canada",
                        listOf(ShelfAlbum("Geogaddi", 2002, listOf("Music Is Math" to 6, "Dawn Chorus" to 12))),
                    ),
                ),
            playlists =
                listOf(
                    ShelfPlaylist("Driving", listOf("Space Debris", "Mysterons", "Cryogen")),
                    ShelfPlaylist("Quiet", listOf("Sour Times")),
                ),
            artists =
                listOf(
                    ShelfArtist(
                        "Muse",
                        listOf(
                            ShelfAlbum(
                                "The Wow! Signal",
                                2014,
                                listOf("Cryogen" to 4, "The Dark Forest" to 1, "Space Debris" to 10, "Hexagons" to 6),
                            ),
                            ShelfAlbum("Absolution", 2003, listOf("Stockholm Syndrome" to 1)),
                        ),
                    ),
                    ShelfArtist(
                        "Portishead",
                        listOf(ShelfAlbum("Dummy", 1994, listOf("Mysterons" to 1, "Sour Times" to 2))),
                    ),
                ),
        )

    private suspend fun albumNamed(
        source: BrowseSource,
        title: String,
    ): LibraryAlbum = source.albums(null).first { it.title == title }

    @Test
    fun `a source that is available answers with what it can see`() =
        runTest {
            val source = createSource(shelf())
            assertTrue("the source under test is available", source.available)

            assertEquals(setOf("Muse", "Portishead"), source.artists().map { it.name }.toSet())
        }

    @Test
    fun `an empty library answers empty`() =
        runTest {
            val source = createSource(Shelf(emptyList()))

            assertEquals(emptyList<Any>(), source.artists())
            assertEquals(emptyList<Any>(), source.albums(null))
        }

    /**
     * Every question that needs no id, asked of a source that cannot reach anything: each
     * one throws [SourceUnreachable]. Questions the source's capabilities do not offer are
     * not asked.
     */
    @Test
    fun `a source that cannot be reached throws SourceUnreachable`() =
        runTest {
            val source = createUnreachableSource(shelf()) ?: return@runTest
            val asks =
                listOfNotNull<Pair<String, suspend () -> List<Any>>>(
                    "artists" to suspend { source.artists() },
                    "albums" to suspend { source.albums(null) },
                    ("search" to suspend { source.search("dark") }).takeIf { source.capabilities.canSearch },
                    ("playlists" to suspend { source.playlists() }).takeIf { source.capabilities.hasPlaylists },
                    ("findArtists" to suspend { source.findArtists("Boards") }).takeIf { source.capabilities.hasCatalogue },
                )

            asks.forEach { (verb, ask) ->
                val outcome = runCatching { ask() }
                assertTrue(
                    "$verb throws SourceUnreachable for an unreachable source, got ${outcome.getOrNull()}",
                    outcome.exceptionOrNull() is SourceUnreachable,
                )
            }
        }

    /** A null artist means every album. */
    @Test
    fun `albums with no artist are every album there is`() =
        runTest {
            val source = createSource(shelf())

            val titles = source.albums(null).map { it.title }

            assertEquals(setOf("The Wow! Signal", "Absolution", "Dummy"), titles.toSet())
        }

    @Test
    fun `an artist's albums are that artist's, and only that artist's`() =
        runTest {
            val source = createSource(shelf())
            val muse = source.artists().first { it.name == "Muse" }

            val titles = source.albums(muse.id).map { it.title }

            assertEquals(setOf("The Wow! Signal", "Absolution"), titles.toSet())
        }

    /** An unknown id is a question with no answer; it does not throw. */
    @Test
    fun `an artist this source has never heard of has no albums`() =
        runTest {
            val source = createSource(shelf())

            assertEquals(emptyList<LibraryAlbum>(), source.albums("no-such-artist"))
        }

    @Test
    fun `an album names its artist`() =
        runTest {
            val source = createSource(shelf())

            assertEquals("Muse", albumNamed(source, "The Wow! Signal").artist)
            assertEquals("Portishead", albumNamed(source, "Dummy").artist)
        }

    /** One album is one row. */
    @Test
    fun `an album appears once`() =
        runTest {
            val source = createSource(shelf())

            val wow = source.albums(null).filter { it.title == "The Wow! Signal" }

            assertEquals(1, wow.size)
        }

    @Test
    fun `an album's tracks come back in the album's own order`() =
        runTest {
            val source = createSource(shelf())

            val titles = source.tracks(albumNamed(source, "The Wow! Signal").id).map { it.title }

            assertEquals(listOf("The Dark Forest", "Cryogen", "Hexagons", "Space Debris"), titles)
        }

    /** Track numbers sort as numbers. */
    @Test
    fun `track ten sorts after track six`() =
        runTest {
            val source = createSource(shelf())

            val titles = source.tracks(albumNamed(source, "The Wow! Signal").id).map { it.title }

            assertTrue(
                "track 10 sorts after track 6",
                titles.indexOf("Space Debris") > titles.indexOf("Hexagons"),
            )
        }

    @Test
    fun `an album nobody has answers with no tracks`() =
        runTest {
            val source = createSource(shelf())

            assertEquals(emptyList<Track>(), source.tracks("no-such-album"))
        }

    @Test
    fun `every track carries something to play`() =
        runTest {
            val source = createSource(shelf())

            val tracks = source.tracks(albumNamed(source, "Dummy").id)

            assertTrue("the album has tracks", tracks.isNotEmpty())
            tracks.forEach { track ->
                assertTrue("every track has an id", track.id.isNotEmpty())
                assertFalse("every row carries a uri", track.uri.isNullOrEmpty())
            }
        }

    /** Search follows the declared capability. */
    @Test
    fun `a source that cannot search answers empty, and one that can finds a match`() =
        runTest {
            val source = createSource(shelf())

            val hits = source.search("dark")

            if (source.capabilities.canSearch) {
                assertTrue("a searchable source finds The Dark Forest", hits.any { it.title == "The Dark Forest" })
            } else {
                assertEquals(emptyList<Track>(), hits)
            }
        }

    @Test
    fun `search finds nothing for a query nothing matches`() =
        runTest {
            val source = createSource(shelf())

            assertEquals(emptyList<Track>(), source.search("zzzzz-nothing-is-called-this"))
        }

    @Test
    fun `search honors its cap`() =
        runTest {
            val source = createSource(shelf())
            if (!source.capabilities.canSearch) return@runTest

            assertTrue(source.search("e", limit = 2).size <= 2)
        }

    @Test
    fun `a source without artists answers no artists`() =
        runTest {
            val source = createSource(shelf())
            if (source.capabilities.hasArtists) return@runTest

            assertEquals(emptyList<Any>(), source.artists())
        }

    @Test
    fun `a source without lists of its own answers empty`() =
        runTest {
            val source = createSource(shelf())
            if (source.capabilities.hasPlaylists) return@runTest

            assertEquals(emptyList<Any>(), source.playlists())
            assertEquals(emptyList<Track>(), source.playlistTracks("anything"))
        }

    @Test
    fun `a source that keeps lists answers with them, named`() =
        runTest {
            val source = createSource(shelf())
            if (!source.capabilities.hasPlaylists) return@runTest

            assertEquals(setOf("Driving", "Quiet"), source.playlists().map { it.name }.toSet())
        }

    /** A list keeps the order it was put in; it is not sorted. */
    @Test
    fun `a list's tracks come back in the order the list keeps them`() =
        runTest {
            val source = createSource(shelf())
            if (!source.capabilities.hasPlaylists) return@runTest
            val driving = source.playlists().first { it.name == "Driving" }

            val titles = source.playlistTracks(driving.id).map { it.title }

            assertEquals(listOf("Space Debris", "Mysterons", "Cryogen"), titles)
        }

    @Test
    fun `a list nobody has answers with no tracks`() =
        runTest {
            val source = createSource(shelf())
            if (!source.capabilities.hasPlaylists) return@runTest

            assertEquals(emptyList<Track>(), source.playlistTracks("no-such-list"))
        }

    @Test
    fun `every row in a list carries something to play`() =
        runTest {
            val source = createSource(shelf())
            if (!source.capabilities.hasPlaylists) return@runTest
            val driving = source.playlists().first { it.name == "Driving" }

            source.playlistTracks(driving.id).forEach { track ->
                assertFalse("every row carries a uri", track.uri.isNullOrEmpty())
            }
        }

    @Test
    fun `a source without a catalog finds no artists`() =
        runTest {
            val source = createSource(shelf())
            if (source.capabilities.hasCatalogue) return@runTest

            assertEquals(emptyList<Any>(), source.findArtists("anything"))
        }

    @Test
    fun `a catalog finds an artist the listener does not have`() =
        runTest {
            val source = createSource(shelf())
            if (!source.capabilities.hasCatalogue) return@runTest

            val found = source.findArtists("Boards of Canada")

            assertTrue("the catalog finds Boards of Canada: $found", found.any { it.name == "Boards of Canada" })
            assertTrue(
                "the found artist is not in the library",
                source.artists().none { it.name == "Boards of Canada" },
            )
        }

    @Test
    fun `a found artist's albums can be listed`() =
        runTest {
            val source = createSource(shelf())
            if (!source.capabilities.hasCatalogue) return@runTest
            val them = source.findArtists("Boards of Canada").first { it.name == "Boards of Canada" }

            val records = source.albums(them.id)

            assertEquals(listOf("Geogaddi"), records.map { it.title })
        }

    @Test
    fun `a found album's tracks can be listed and carry a uri`() =
        runTest {
            val source = createSource(shelf())
            if (!source.capabilities.hasCatalogue) return@runTest
            val them = source.findArtists("Boards of Canada").first { it.name == "Boards of Canada" }
            val geogaddi = source.albums(them.id).first()

            val tracks = source.tracks(geogaddi.id)

            assertEquals(listOf("Music Is Math", "Dawn Chorus"), tracks.map { it.title })
            tracks.forEach { assertFalse("every found track carries a uri", it.uri.isNullOrEmpty()) }
        }

    @Test
    fun `finding nobody answers empty`() =
        runTest {
            val source = createSource(shelf())

            assertEquals(emptyList<Any>(), source.findArtists("zzzzz-nobody-is-called-this"))
        }

    @Test
    fun `a catalog artist is not listed in the library`() =
        runTest {
            val source = createSource(shelf())

            assertTrue(
                "the library does not list the catalog artist",
                source.artists().none { it.name == "Boards of Canada" },
            )
        }
}
