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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Renaming and deleting the stations and the saved lists, from their rows' menus. */
class LibraryKeptRowsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val temp = TemporaryFolder()

    private object Shelf : BrowseSource {
        override val capabilities = BrowseCapabilities()
        override val available = true

        override suspend fun artists() = listOf(LibraryArtist("a1", "Autechre"))

        override suspend fun albums(artistId: String?) = listOf(LibraryAlbum("l1", "Amber", "Autechre"))

        override suspend fun tracks(albumId: String) = emptyList<Track>()
    }

    private val state = WinampState()
    private val stations by lazy { StationStore(File(temp.newFolder("radio"), "stations.m3u")) }
    private val lists by lazy { PlaylistLibrary(temp.newFolder("playlists")) }

    private fun library(): LibraryOps {
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        return LibraryOps({ Shelf }, facade, state, scope, lists, stations, io = Dispatchers.Unconfined, words = EnglishWords)
            .also { it.open() }
    }

    private fun songs() = listOf(Track("r1", "A", "One", 60_000, uri = "content://1"))

    @Test
    fun `a station is renamed from its row, on the page and in the file`() {
        stations.add("Example FM", "http://radio.example.com/stream")
        val ops = library()
        ops.switchCategory(LibraryOps.Category.RADIO)
        assertEquals(LibraryOps.Kept.STATION, ops.keptAt(0))

        ops.promptRenameRow(0)
        val prompt = state.namePrompt
        assertEquals("Station name", prompt?.title)
        assertEquals("Example FM", prompt?.initial)
        prompt?.onSubmit?.invoke("  Late Example  ")

        assertNull(state.namePrompt)
        assertEquals(listOf("Late Example"), ops.rows.map { it.label })
        assertEquals(listOf("Late Example"), stations.list().map { it.title })
    }

    @Test
    fun `a station is deleted from its row once the listener says so`() {
        stations.add("Example FM", "http://radio.example.com/stream")
        stations.add("Radio Example", "https://example.com/fm")
        val ops = library()
        ops.switchCategory(LibraryOps.Category.RADIO)

        ops.promptDeleteRow(0)
        val prompt = state.prompt
        assertEquals("Delete Example FM?", prompt?.title)
        assertEquals(listOf("Example FM", "Radio Example"), ops.rows.map { it.label })
        prompt?.onConfirm?.invoke()

        assertEquals(listOf("Radio Example"), ops.rows.map { it.label })
        assertEquals(listOf("Radio Example"), stations.list().map { it.title })
    }

    @Test
    fun `a saved list is renamed from its row`() {
        lists.save("Road trip", songs())
        val ops = library()
        ops.switchCategory(LibraryOps.Category.LISTS)
        assertEquals(LibraryOps.Kept.LIST, ops.keptAt(0))

        ops.promptRenameRow(0)
        assertEquals("Rename list", state.namePrompt?.title)
        assertEquals("Road trip", state.namePrompt?.initial)
        state.namePrompt?.onSubmit?.invoke("Weekend")

        assertEquals(listOf("Weekend"), ops.rows.map { it.label })
        assertEquals(listOf("One"), lists.load("Weekend")?.map { it.title })
    }

    @Test
    fun `a list is not renamed over another one`() {
        lists.save("Gym", songs())
        lists.save("Road trip", songs())
        val ops = library()
        ops.switchCategory(LibraryOps.Category.LISTS)

        ops.promptRenameRow(1) // Road trip
        state.namePrompt?.onSubmit?.invoke("Gym")

        assertEquals("CAN'T RENAME", ops.flash)
        assertEquals(listOf("Gym", "Road trip"), ops.rows.map { it.label })
    }

    @Test
    fun `an artist's row has nothing to rename or delete`() {
        val ops = library()

        assertNull(ops.keptAt(0))
        ops.promptRenameRow(0)
        assertNull(state.namePrompt)
    }
}
