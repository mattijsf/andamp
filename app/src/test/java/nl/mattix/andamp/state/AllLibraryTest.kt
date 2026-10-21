// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.BrowseSourceContractTest
import nl.mattix.andamp.core.playback.SourceUnreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every source as one library: the shared browse contract over one source, and how two
 * sources become one shelf.
 */
class AllLibraryTest : BrowseSourceContractTest() {
    private val phone = MusicSource.LOCAL
    private val server = MusicSource("EXAMPLE", "Example")

    private fun together(vararg members: Pair<MusicSource, BrowseSource>) =
        AllLibrary { members.map { (source, library) -> AllLibrary.Member(source, library, SourceMark.letter(source.label)) } }

    override fun createSource(shelf: Shelf): BrowseSource = together(server to ShelfSource(shelf))

    override fun createUnreachableSource(shelf: Shelf): BrowseSource = together(server to ShelfSource(shelf, reachable = false))

    /** The phone has Muse's two albums; the server has Muse's Absolution too, and Portishead. */
    private val phoneShelf =
        Shelf(
            listOf(
                ShelfArtist(
                    "Muse",
                    listOf(
                        ShelfAlbum("The Wow! Signal", 2014, listOf("Cryogen" to 4, "The Dark Forest" to 1)),
                        ShelfAlbum("Absolution", 2003, listOf("Stockholm Syndrome" to 1)),
                    ),
                ),
            ),
        )

    private val serverShelf =
        Shelf(
            listOf(
                ShelfArtist("MUSE", listOf(ShelfAlbum("absolution", 2003, listOf("Stockholm Syndrome" to 1, "Hysteria" to 8)))),
                ShelfArtist("Portishead", listOf(ShelfAlbum("Dummy", 1994, listOf("Mysterons" to 1)))),
            ),
            playlists = listOf(ShelfPlaylist("Driving", listOf("Mysterons"))),
        )

    @Test
    fun `an artist two sources have is one row`() =
        runTest {
            val library = together(phone to ShelfSource(phoneShelf), server to ShelfSource(serverShelf))

            assertEquals(listOf("Muse", "Portishead"), library.artists().map { it.name })
        }

    @Test
    fun `an album two sources have is one row, marked with both, and plays the phone's copy`() =
        runTest {
            val library = together(phone to ShelfSource(phoneShelf), server to ShelfSource(serverShelf))
            val muse = library.artists().first { it.name == "Muse" }

            val albums = library.albums(muse.id)
            val absolution = albums.single { it.title == "Absolution" }

            assertEquals(listOf("Absolution", "The Wow! Signal"), albums.map { it.title })
            assertEquals(listOf("T", "E"), library.marksOf(absolution.id).map { it.letter })
            assertEquals(
                "the phone's copy has one track",
                listOf("Stockholm Syndrome"),
                library.tracks(absolution.id).map { it.title },
            )
        }

    @Test
    fun `when the first copy cannot be reached, the next one plays`() =
        runTest {
            val down = ShelfSource(phoneShelf)
            val library = together(phone to down, server to ShelfSource(serverShelf))
            val absolution = library.albums(null).single { it.title == "Absolution" }

            down.reachable = false

            assertEquals(listOf("Stockholm Syndrome", "Hysteria"), library.tracks(absolution.id).map { it.title })
        }

    @Test
    fun `a source that cannot be reached is left out, and the answer says it is not whole`() =
        runTest {
            val library = together(phone to ShelfSource(phoneShelf), server to ShelfSource(serverShelf, reachable = false))

            assertEquals(listOf("Muse"), library.artists().map { it.name })
            assertFalse(library.whole)
        }

    @Test
    fun `with no source to be reached, the question is not answered`() =
        runTest {
            val library =
                together(
                    phone to ShelfSource(phoneShelf, reachable = false),
                    server to ShelfSource(serverShelf, reachable = false),
                )

            assertTrue(runCatching { library.artists() }.exceptionOrNull() is SourceUnreachable)
        }

    @Test
    fun `a source's own lists lead back to it`() =
        runTest {
            val library = together(phone to ShelfSource(phoneShelf), server to ShelfSource(serverShelf))

            val driving = library.playlists().single { it.name == "Driving" }

            assertEquals(listOf("Mysterons"), library.playlistTracks(driving.id).map { it.title })
            assertEquals("a list is marked by its source", "E", library.markOfList(driving.id)?.letter)
            assertEquals("a list from nowhere has no mark", null, library.markOfList("Driving"))
        }

    @Test
    fun `a source's mark is found by the source, and folders and genres come from none of these`() {
        val library = together(phone to ShelfSource(phoneShelf), server to ShelfSource(serverShelf))

        assertEquals("E", library.markOfSource(server)?.letter)
        assertEquals(null, library.markOfSource(MusicSource("ELSEWHERE", "Elsewhere")))
        assertEquals("neither shelf knows folders or genres", null, library.foldersAndGenresFrom())
    }

    /** A library that knows folders and genres, as the phone does and a source that gives them does. */
    private class Filed(
        shelf: BrowseSource,
        private val folders: List<Pair<String, Int?>> = emptyList(),
        private val genres: List<Pair<String, Int?>> = emptyList(),
        private val tracks: Map<String, List<Track>> = emptyMap(),
    ) : BrowseSource by shelf,
        FoldersAndGenres {
        override suspend fun folders() = folders

        override suspend fun folderTracks(folder: String) = tracks[folder].orEmpty()

        override suspend fun genres() = genres

        override suspend fun genreTracks(genre: String) = tracks[genre].orEmpty()
    }

    @Test
    fun `the folders and genres of every source that gives them are one list, unmarked`() =
        runTest {
            val library =
                together(
                    phone to Filed(ShelfSource(phoneShelf), folders = listOf("Music/Muse/" to 3), genres = listOf("Rock" to 3)),
                    server to
                        Filed(
                            ShelfSource(serverShelf),
                            folders = listOf("music/Dummy" to null),
                            genres =
                                listOf(
                                    "rock" to null,
                                    "Trip Hop" to 1,
                                ),
                        ),
                )

            assertEquals(listOf("music/Dummy" to null, "Music/Muse/" to 3), library.folders())
            assertEquals("a count only some sources know is theirs", listOf("Rock" to 3, "Trip Hop" to 1), library.genres())
            assertEquals("several sources give them", null, library.foldersAndGenresFrom())
        }

    @Test
    fun `a genre's tracks come from every source that has it, each song once`() =
        runTest {
            val stockholm = Track("p1", "Muse", "Stockholm Syndrome", 1)
            val library =
                together(
                    phone to Filed(ShelfSource(phoneShelf), tracks = mapOf("Rock" to listOf(stockholm))),
                    server to
                        Filed(
                            ShelfSource(serverShelf),
                            tracks =
                                mapOf(
                                    "Rock" to
                                        listOf(
                                            Track("s1", "MUSE", "Stockholm Syndrome", 1),
                                            Track("s2", "Muse", "Hysteria", 1),
                                        ),
                                ),
                        ),
                )

            assertEquals(listOf("p1", "s2"), library.genreTracks("Rock").map { it.id })
        }

    @Test
    fun `folders and genres one source alone gives come from that source`() {
        val library = together(phone to ShelfSource(phoneShelf), server to Filed(ShelfSource(serverShelf)))

        assertEquals(server, library.foldersAndGenresFrom())
    }

    @Test
    fun `a list the library makes knows the one source its songs come from`() {
        val local = Track("a", "Muse", "Cryogen", 4, uri = "content://media/external/audio/media/1")
        val remote = Track("b", "Portishead", "Mysterons", 1, uri = "example:track:1")

        assertEquals(MusicSource.LOCAL.id, SmartList("Recently Added", listOf(local to null)).from?.id)
        assertEquals(null, SmartList("Recently Played", listOf(local to null, remote to null)).from)
    }

    @Test
    fun `a track two sources have is found once, the phone's`() =
        runTest {
            val library = together(phone to ShelfSource(phoneShelf), server to ShelfSource(serverShelf))

            val hits = library.search("stockholm", 10)

            assertEquals(1, hits.size)
            assertTrue(hits.single().uri!!.contains("Stockholm"))
        }

    @Test
    fun `search in sections gathers every source's artists and albums`() =
        runTest {
            val library = together(phone to ShelfSource(phoneShelf), server to ShelfSource(serverShelf))

            val found = library.searchSections("s")

            assertTrue(found.tracks.isNotEmpty())
            assertEquals("a source without sections still gives its tracks", emptyList<Any>(), found.artists)
        }
}
