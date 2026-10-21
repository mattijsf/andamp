// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.LibraryGroup
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseGroups
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.BrowseSourceContractTest
import nl.mattix.andamp.core.playback.SourceUnreachable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A source behind a server that gives its folders and genres: the shared browse contract, the
 * lists kept in the index, and a group's tracks asked of the source by the ids behind its name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupedSourceLibraryTest : BrowseSourceContractTest() {
    private val index = LibraryIndex(ApplicationProvider.getApplicationContext<Application>(), null)

    /** A source's folders and genres, and what it was asked. */
    private class Filed(
        shelf: BrowseSource,
    ) : BrowseSource by shelf,
        BrowseGroups {
        val asked = mutableListOf<String>()
        var reachable = true
        var folders = listOf(LibraryGroup("/srv/music/a", "music/a", 2), LibraryGroup("/srv/music/b", "music/b"))

        override suspend fun folders(): List<LibraryGroup> = answer("folders") { folders }

        override suspend fun folderTracks(id: String): List<Track> = answer("folderTracks $id") { listOf(track(id)) }

        override suspend fun genres(): List<LibraryGroup> =
            answer("genres") {
                listOf(LibraryGroup("g1", "Rock", 3), LibraryGroup("g2", "Jazz"), LibraryGroup("g3", "Rock", 1))
            }

        override suspend fun genreTracks(id: String): List<Track> = answer("genreTracks $id") { listOf(track(id)) }

        private fun <T> answer(
            question: String,
            give: () -> T,
        ): T {
            asked += question
            if (!reachable) throw SourceUnreachable("the server is down")
            return give()
        }

        private fun track(id: String) = Track(id, "Artist", "Title $id", 1_000)
    }

    private fun grouped(
        upstream: Filed,
        offered: GroupsOffered = GroupsOffered(folders = true, genres = true),
    ) = GroupedSourceLibrary(
        key = "source:example",
        upstream = upstream,
        groups = upstream,
        offered = offered,
        index = index,
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
    )

    override fun createSource(shelf: Shelf): BrowseSource = grouped(Filed(ShelfSource(shelf)))

    override fun createUnreachableSource(shelf: Shelf): BrowseSource = grouped(Filed(ShelfSource(shelf, reachable = false)))

    @Test
    fun `the first folders are asked behind an empty answer and shown when they arrive`() =
        runTest {
            val upstream = Filed(ShelfSource(shelf()))
            val library = grouped(upstream)

            val first = library.folders()

            assertEquals("the page does not wait for them", emptyList<Pair<String, Int?>>(), first)
            assertEquals("they were asked behind it", listOf("folders"), upstream.asked)
            assertEquals("and the window is told", 1, library.revision.value)
            assertEquals(listOf("music/a" to 2, "music/b" to null), library.folders())
            assertEquals("the second look is the index's", listOf("folders"), upstream.asked)
        }

    @Test
    fun `kept folders are shown when the source cannot be reached`() =
        runTest {
            grouped(Filed(ShelfSource(shelf()))).folders()
            val down = Filed(ShelfSource(shelf())).apply { reachable = false }

            // the app starts again with the server down: a new library over the same index
            assertEquals(listOf("music/a", "music/b"), grouped(down).folders().map { it.first })
        }

    @Test
    fun `genres of one name are one row, and its tracks are asked by every id behind it`() =
        runTest {
            val upstream = Filed(ShelfSource(shelf()))
            val library = grouped(upstream)
            library.genres()
            library.folders()

            assertEquals(listOf("Rock" to 4, "Jazz" to null), library.genres())
            assertEquals(listOf("g1", "g3"), library.genreTracks("Rock").map { it.id })
            assertEquals(listOf("/srv/music/b"), library.folderTracks("music/b").map { it.id })
            assertEquals(
                listOf("genres", "folders", "genreTracks g1", "genreTracks g3", "folderTracks /srv/music/b"),
                upstream.asked,
            )
        }

    @Test
    fun `a view the source does not give is empty, and nothing is asked for it`() =
        runTest {
            val upstream = Filed(ShelfSource(shelf()))
            val library = grouped(upstream, GroupsOffered(folders = false, genres = true))

            assertEquals(emptyList<Pair<String, Int?>>(), library.folders())
            assertEquals(emptyList<Track>(), library.folderTracks("music/a"))
            assertEquals(emptyList<String>(), upstream.asked)
        }

    @Test
    fun `a name the index does not know has no tracks`() =
        runTest {
            val upstream = Filed(ShelfSource(shelf()))

            assertEquals(emptyList<Track>(), grouped(upstream).genreTracks("Polka"))
            assertEquals(emptyList<String>(), upstream.asked)
        }
}
