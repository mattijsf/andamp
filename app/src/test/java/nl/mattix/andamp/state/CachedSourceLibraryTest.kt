// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.BrowseSourceContractTest
import nl.mattix.andamp.core.playback.SourceUnreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A source behind a server, kept in the library's index: the shared browse contract, and when
 * the source is asked and when the index answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CachedSourceLibraryTest : BrowseSourceContractTest() {
    private val index = LibraryIndex(ApplicationProvider.getApplicationContext<Application>(), null)

    /** The clock the library dates its answers by; moved by hand. */
    private var now = 1_000_000L

    /** A source over [shelf], counting what it is asked; [reachable] false makes every question fail. */
    private class ShelfSource(
        private var shelf: Shelf,
        var reachable: Boolean = true,
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities(canSearch = true, hasPlaylists = true, hasCatalogue = true)
        override val available = true
        val asked = mutableListOf<String>()

        fun replace(with: Shelf) {
            shelf = with
        }

        private fun answer(question: String) {
            asked += question
            if (!reachable) throw SourceUnreachable("the server is down")
        }

        private fun artistId(name: String) = "ar-$name"

        private fun albumId(title: String) = "al-$title"

        private fun everyone() = shelf.artists + shelf.catalogue

        private fun trackOf(
            artist: String,
            title: String,
        ) = Track("t-$title", artist, title, 1000, uri = "example:track:$title")

        override suspend fun artists(): List<LibraryArtist> {
            answer("artists")
            return shelf.artists.map { LibraryArtist(artistId(it.name), it.name, albumCount = it.albums.size) }
        }

        override suspend fun albums(artistId: String?): List<LibraryAlbum> {
            answer("albums:$artistId")
            val whose = if (artistId == null) shelf.artists else everyone().filter { artistId(it.name) == artistId }
            return whose.flatMap { artist ->
                artist.albums.map { LibraryAlbum(albumId(it.title), it.title, artist.name, it.year) }
            }
        }

        override suspend fun tracks(albumId: String): List<Track> {
            answer("tracks:$albumId")
            for (artist in everyone()) {
                val album = artist.albums.firstOrNull { albumId(it.title) == albumId } ?: continue
                return album.tracks.sortedBy { it.second ?: -1 }.map { (title, _) -> trackOf(artist.name, title) }
            }
            return emptyList()
        }

        override suspend fun search(
            query: String,
            limit: Int,
        ): List<Track> {
            answer("search:$query")
            return shelf.artists
                .flatMap { artist -> artist.albums.flatMap { album -> album.tracks.map { trackOf(artist.name, it.first) } } }
                .filter { it.title.contains(query, ignoreCase = true) }
                .take(limit)
        }

        override suspend fun playlists(): List<LibraryPlaylist> {
            answer("playlists")
            return shelf.playlists.map { LibraryPlaylist("pl-${it.name}", it.name, it.titles.size) }
        }

        override suspend fun playlistTracks(id: String): List<Track> {
            answer("playlist:$id")
            val list = shelf.playlists.firstOrNull { "pl-${it.name}" == id } ?: return emptyList()
            return list.titles.map { trackOf("", it) }
        }

        override suspend fun findArtists(
            query: String,
            limit: Int,
        ): List<LibraryArtist> {
            answer("find:$query")
            return shelf.catalogue
                .filter {
                    it.name.contains(
                        query,
                        ignoreCase = true,
                    )
                }.map { LibraryArtist(artistId(it.name), it.name) }
        }
    }

    private fun cached(
        upstream: BrowseSource,
        whole: Boolean = true,
    ) = CachedSourceLibrary(
        key = "source:example",
        upstream = upstream,
        index = index,
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        clock = { now },
        wholeOf = { whole },
    )

    override fun createSource(shelf: Shelf): BrowseSource = cached(ShelfSource(shelf))

    override fun createUnreachableSource(shelf: Shelf): BrowseSource = cached(ShelfSource(shelf, reachable = false))

    @Test
    fun `a second look is answered from the index, without asking the source`() =
        runTest {
            val upstream = ShelfSource(shelf())
            val library = cached(upstream)

            library.artists()
            library.artists()

            assertEquals(listOf("artists"), upstream.asked)
        }

    @Test
    fun `an answer kept from before the app started is shown at once, and a newer one after it`() =
        runTest {
            cached(ShelfSource(shelf())).artists()
            val upstream = ShelfSource(shelf())
            upstream.replace(shelf().copy(artists = shelf().artists + ShelfArtist("Air", emptyList())))
            // the app starts again: a new library over the same index
            val library = cached(upstream)

            assertEquals("the kept answer comes first", 2, library.artists().size)
            assertEquals("the source was asked behind it", listOf("artists"), upstream.asked)
            assertEquals(1, library.revision.value)
            assertEquals(3, library.artists().size)
        }

    @Test
    fun `the source is asked again once a kept answer is an hour old`() =
        runTest {
            val upstream = ShelfSource(shelf())
            val library = cached(upstream)
            library.artists()

            now += CachedSourceLibrary.MAX_AGE_MS
            library.artists()

            assertEquals(listOf("artists", "artists"), upstream.asked)
            assertEquals("the same answer is not shown again", 0, library.revision.value)
        }

    @Test
    fun `a kept answer is given when the source cannot be reached`() =
        runTest {
            cached(ShelfSource(shelf())).artists()
            val down = ShelfSource(shelf(), reachable = false)

            val library = cached(down)

            assertEquals(setOf("Muse", "Portishead"), library.artists().map { it.name }.toSet())
            assertEquals(0, library.revision.value)
        }

    @Test
    fun `an album's tracks are kept once it was opened`() =
        runTest {
            val upstream = ShelfSource(shelf())
            val library = cached(upstream)
            val dummy = library.albums(null).first { it.title == "Dummy" }

            library.tracks(dummy.id)
            library.tracks(dummy.id)

            assertEquals(listOf("albums:null", "tracks:al-Dummy"), upstream.asked)
        }

    @Test
    fun `an artist's albums are kept in the order the source gave them`() =
        runTest {
            val upstream = ShelfSource(shelf())
            val library = cached(upstream)
            val muse = library.artists().first { it.name == "Muse" }

            val first = library.albums(muse.id).map { it.title }
            val again = library.albums(muse.id).map { it.title }

            assertEquals(listOf("The Wow! Signal", "Absolution"), first)
            assertEquals(first, again)
            assertEquals(listOf("artists", "albums:ar-Muse"), upstream.asked)
        }

    @Test
    fun `the catalog is not kept`() =
        runTest {
            val upstream = ShelfSource(shelf())
            val library = cached(upstream)
            library.artists()
            val them = library.findArtists("Boards").single()

            library.albums(them.id)
            library.albums(them.id)

            assertEquals(2, upstream.asked.count { it == "albums:${them.id}" })
            assertFalse("a catalog album is not on the ALBUMS page", library.albums(null).any { it.title == "Geogaddi" })
        }

    @Test
    fun `a walk the source stopped part way is told, also when it is answered from the index`() =
        runTest {
            val library = cached(ShelfSource(shelf()), whole = false)
            library.artists()
            assertFalse(library.whole)

            val later = cached(ShelfSource(shelf(), reachable = false))
            later.artists()
            assertFalse(later.whole)
        }

    @Test
    fun `search falls back on the kept tracks when the source cannot be reached`() =
        runTest {
            val library = cached(ShelfSource(shelf()))
            library.artists()
            library.tracks(library.albums(null).first { it.title == "Dummy" }.id)

            val down = cached(ShelfSource(shelf(), reachable = false))

            assertEquals(listOf("Sour Times"), down.search("sour", 10).map { it.title })
            assertEquals(listOf("Sour Times"), down.searchSections("sour").tracks.map { it.title })
            assertEquals(listOf("Portishead"), down.searchSections("portis").artists.map { it.name })
        }

    @Test
    fun `a source whose account went is asked again from scratch`() =
        runTest {
            val upstream = ShelfSource(shelf())
            val library = cached(upstream)
            library.artists()

            index.clear("source:example")
            library.artists()

            assertEquals(listOf("artists", "artists"), upstream.asked)
        }
}
