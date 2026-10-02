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
import org.junit.Assert.assertEquals
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
