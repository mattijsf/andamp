// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * The row menu's verbs, which act on a library row without opening it: an
 * artist row stands for every track on every one of its albums, an album row
 * for its tracks, a track row for itself.
 */
class LibraryRowMenuTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val temp = TemporaryFolder()

    /** One artist with two albums, two tracks each, so an artist is four tracks. */
    private class Shelf : BrowseSource {
        override val available = true
        override val capabilities = BrowseCapabilities()

        override suspend fun artists() = listOf(LibraryArtist("a1", "Autechre", albumCount = 2))

        override suspend fun albums(artistId: String?) =
            listOf(
                LibraryAlbum("l1", "Amber", "Autechre", year = 1994),
                LibraryAlbum("l2", "Tri Repetae", "Autechre", year = 1995),
            )

        override suspend fun tracks(albumId: String) =
            when (albumId) {
                "l1" -> listOf(track("t1", "Foil"), track("t2", "Montreal"))
                "l2" -> listOf(track("t3", "Dael"), track("t4", "Clipper"))
                else -> emptyList()
            }

        companion object {
            fun track(
                id: String,
                title: String,
            ) = Track(id, "Autechre", title, 6_000, uri = "content://$id")
        }
    }

    private fun player(): Triple<LibraryOps, WinampState, PlayerFacade> {
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(emptyList(), scope))
        return Triple(LibraryOps({ Shelf() }, facade, state, scope, lists(), stations()), state, facade)
    }

    private fun lists() = PlaylistLibrary(temp.newFolder("playlists"))

    private class Saved(
        val ops: LibraryOps,
        val state: WinampState,
        val shelf: PlaylistLibrary,
        val facade: PlayerFacade,
    )

    /** The LISTS tab over two saved lists, "Gym" then "Road trip". */
    private fun savedLists(): Saved {
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(emptyList(), scope))
        val shelf = lists()
        shelf.save("Road trip", listOf(Shelf.track("r1", "One"), Shelf.track("r2", "Two")))
        shelf.save("Gym", listOf(Shelf.track("g1", "Three")))
        val ops = LibraryOps({ Shelf() }, facade, state, scope, shelf, stations())
        ops.open()
        ops.switchCategory(LibraryOps.Category.LISTS)
        return Saved(ops, state, shelf, facade)
    }

    private fun labels(
        ops: LibraryOps,
        row: Int,
    ) = libraryRowMenu(ops, row, ops.rows[row].label, MenuAnchor("library", 0, 0, 0, 0))
        .items
        .filterIsInstance<AmpMenuItem.Action>()
        .map { it.label }

    private fun stations() = StationStore(java.io.File(temp.newFolder("radio"), "stations.m3u"))

    @Test
    fun `playing an artist row plays every track under it`() {
        val (ops, _, facade) = player()
        ops.open()

        ops.playRow(0)

        assertEquals(
            listOf("t1", "t2", "t3", "t4"),
            facade.state.value.queue
                .map { it.id },
        )
    }

    @Test
    fun `enqueueing an album row appends only that album`() {
        val (ops, _, facade) = player()
        ops.open()
        ops.tapRow(0) // into Autechre

        ops.enqueueRow(1) // Tri Repetae

        assertEquals(
            listOf("t3", "t4"),
            facade.state.value.queue
                .map { it.id },
        )
    }

    @Test
    fun `enqueue next puts the row straight after what is playing`() {
        val (ops, state, facade) = player()
        ops.open()
        ops.playRow(0) // the artist, four tracks, playing the first
        facade.playAt(1) // now on t2

        ops.tapRow(0) // into Autechre
        ops.enqueueNextRow(0) // Amber, straight after t2

        assertEquals(
            listOf("t1", "t2", "t1", "t2", "t3", "t4"),
            facade.state.value.queue
                .map { it.id },
        )
    }

    @Test
    fun `enqueue next into an empty queue adds the row's tracks`() {
        val (ops, _, facade) = player()
        ops.open()
        ops.tapRow(0)

        ops.enqueueNextRow(0)

        assertEquals(
            listOf("t1", "t2"),
            facade.state.value.queue
                .map { it.id },
        )
    }

    @Test
    fun `a saved list's row offers Delete list, a track in it does not`() {
        val ops = savedLists().ops
        assertEquals(listOf("Play", "Enqueue", "Enqueue next", "Delete list"), labels(ops, 0))

        ops.tapRow(0) // into Gym
        assertEquals(listOf("Play", "Enqueue", "Enqueue next"), labels(ops, 0))
    }

    @Test
    fun `an artist's row has nothing to delete`() {
        val (ops, _, _) = player()
        ops.open()

        assertEquals(listOf("Play", "Enqueue", "Enqueue next"), labels(ops, 0))
    }

    @Test
    fun `playing a saved list's row plays the list`() {
        val saved = savedLists()

        saved.ops.playRow(1) // Road trip

        assertEquals(
            listOf("r1", "r2"),
            saved.facade.state.value.queue
                .map { it.id },
        )
    }

    @Test
    fun `Delete list asks first, then takes the list off the shelf and the page`() {
        val saved = savedLists()
        val (ops, state, shelf) = Triple(saved.ops, saved.state, saved.shelf)

        ops.promptDeleteRow(1) // Road trip

        assertEquals("Delete Road trip?", state.prompt?.title)
        assertEquals("nothing is deleted before the answer", listOf("Gym", "Road trip"), shelf.list().map { it.name })

        state.prompt?.onConfirm?.invoke()

        assertNull(state.prompt)
        assertEquals(listOf("Gym"), shelf.list().map { it.name })
        assertEquals(listOf("Gym"), ops.rows.map { it.label })
        assertEquals("1 LIST", ops.status)
    }

    @Test
    fun `only the inside of a saved list can be deleted as a page`() {
        val ops = savedLists().ops
        assertFalse("the page of lists has rows to delete, not itself", ops.canDeletePage)

        ops.tapRow(0) // into Gym
        assertTrue(ops.canDeletePage)

        ops.switchCategory(LibraryOps.Category.ARTISTS)
        ops.tapRow(0) // into Autechre
        ops.tapRow(0) // into Amber, an album's tracks
        assertFalse(ops.canDeletePage)
    }

    @Test
    fun `deleting an opened list goes back up to the lists that are left`() {
        val saved = savedLists()
        val (ops, state) = saved.ops to saved.state
        ops.tapRow(1) // into Road trip

        ops.promptDeletePage()

        assertEquals("Delete Road trip?", state.prompt?.title)
        assertEquals("still inside the list until the answer", "Road trip", ops.header)

        state.prompt?.onConfirm?.invoke()

        assertEquals("LISTS", ops.header)
        assertEquals(0, ops.depth)
        assertEquals(listOf("Gym"), ops.rows.map { it.label })
        assertEquals(listOf("Gym"), saved.shelf.list().map { it.name })
    }

    @Test
    fun `a list deleted elsewhere leaves the page of lists`() {
        val saved = savedLists()
        saved.shelf.delete("Gym")

        saved.ops.savedListDeleted("Gym")

        assertEquals(listOf("Road trip"), saved.ops.rows.map { it.label })
    }

    @Test
    fun `a list deleted elsewhere closes its own page, and another list's page stays open`() {
        val saved = savedLists()
        val ops = saved.ops
        ops.tapRow(1) // into Road trip
        saved.shelf.delete("Gym")
        ops.savedListDeleted("Gym")

        assertEquals("Road trip", ops.header)
        ops.up()
        assertEquals("going up finds the lists as they are now", listOf("Road trip"), ops.rows.map { it.label })

        ops.tapRow(0) // into Road trip again
        saved.shelf.delete("Road trip")
        ops.savedListDeleted("Road trip")

        assertEquals("LISTS", ops.header)
        assertTrue(ops.rows.isEmpty())
    }

    @Test
    fun `deleting a list leaves the queue it filled alone`() {
        val saved = savedLists()
        saved.ops.playRow(1) // Road trip

        saved.ops.promptDeleteRow(1)
        saved.state.prompt
            ?.onConfirm
            ?.invoke()

        assertEquals(
            listOf("r1", "r2"),
            saved.facade.state.value.queue
                .map { it.id },
        )
    }

    @Test
    fun `enqueueing a track row appends only that track`() {
        val (ops, _, facade) = player()
        ops.open()
        ops.tapRow(0) // into Autechre
        ops.tapRow(0) // into Amber, a leaf

        ops.enqueueRow(1) // Montreal

        assertEquals(
            listOf("t2"),
            facade.state.value.queue
                .map { it.id },
        )
    }
}
