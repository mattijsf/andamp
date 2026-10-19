// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.test.runTest
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
        AllLibrary { members.map { (source, library) -> AllLibrary.Member(source, library, source.label.take(1)) } }

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
            assertEquals("TE", library.marksOf(absolution.id))
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
