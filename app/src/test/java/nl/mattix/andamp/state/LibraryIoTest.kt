// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import nl.mattix.andamp.backend.mock.MockBackend
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
 * The saved lists and the stations are files, read and written on the library's io
 * context and not on the thread that asked.
 *
 * The io context here holds its work until [release], so each case can see that nothing
 * was read or written before it ran. Everything else runs where it is called.
 */
class LibraryIoTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val held = TestCoroutineScheduler()

    @get:Rule
    val temp = TemporaryFolder()

    /** A source that keeps no playlists, so the LISTS tab shows the saved ones. */
    private class Shelf : BrowseSource {
        override val available = true
        override val capabilities = BrowseCapabilities()

        override suspend fun artists() = emptyList<LibraryArtist>()

        override suspend fun albums(artistId: String?) = emptyList<LibraryAlbum>()

        override suspend fun tracks(albumId: String) = emptyList<Track>()
    }

    private val lists by lazy { PlaylistLibrary(temp.newFolder("playlists")) }
    private val stations by lazy { StationStore(File(temp.newFolder("radio"), "stations.m3u")) }
    private val state = WinampState()

    private val ops by lazy {
        LibraryOps(
            { Shelf() },
            PlayerFacade(MockBackend(emptyList(), scope)),
            state,
            scope,
            lists,
            stations,
            io = StandardTestDispatcher(held),
            words = EnglishWords,
        )
    }

    private fun release() = held.advanceUntilIdle()

    private val track = Track("g1", "Artist", "Song", 6_000, uri = "content://g1")

    @Test
    fun `the saved lists are read on the io context`() {
        lists.save("Gym", listOf(track))
        ops.open()
        release()

        ops.switchCategory(LibraryOps.Category.LISTS)

        assertTrue("the page waits for the read", ops.loading)
        release()
        assertFalse(ops.loading)
        assertEquals(listOf("Gym"), ops.rows.map { it.label })
    }

    @Test
    fun `a saved list's songs are read on the io context`() {
        lists.save("Gym", listOf(track))
        ops.open()
        release()
        ops.switchCategory(LibraryOps.Category.LISTS)
        release()

        ops.tapRow(0)

        assertTrue("the page waits for the read", ops.loading)
        release()
        assertEquals("Gym", ops.header)
        assertEquals(listOf("1. Song"), ops.rows.map { it.label })
    }

    @Test
    fun `a deleted list is taken off the shelf on the io context`() {
        lists.save("Gym", listOf(track))
        lists.save("Road trip", listOf(track))
        ops.open()
        release()
        ops.switchCategory(LibraryOps.Category.LISTS)
        release()

        ops.promptDeleteRow(0)
        state.prompt?.onConfirm?.invoke()

        assertEquals("nothing is deleted before the io context runs", 2, lists.list().size)
        release()
        assertEquals(listOf("Road trip"), lists.list().map { it.name })
        assertEquals(listOf("Road trip"), ops.rows.map { it.label })
    }

    @Test
    fun `the stations are read on the io context`() {
        stations.add("Example FM", "http://radio.example.com/stream")
        ops.open()
        release()

        ops.switchCategory(LibraryOps.Category.RADIO)

        assertTrue("the page waits for the read", ops.loading)
        release()
        assertEquals(listOf("Example FM"), ops.rows.map { it.label })
    }

    @Test
    fun `a new station is written on the io context`() {
        ops.open()
        release()
        ops.switchCategory(LibraryOps.Category.RADIO)
        release()

        ops.promptNewStation()
        state.namePrompt?.onSubmit?.invoke("http://radio.example.com/stream")
        state.namePrompt?.onSubmit?.invoke("Late Night Example")

        assertTrue("nothing is written before the io context runs", stations.list().isEmpty())
        release()
        assertEquals(listOf("Late Night Example"), stations.list().map { it.title })
        assertEquals(listOf("Late Night Example"), ops.rows.map { it.label })
    }
}
