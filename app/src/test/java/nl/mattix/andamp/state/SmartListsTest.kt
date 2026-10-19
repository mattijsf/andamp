// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The lists the library makes itself, at the top of LISTS, and which songs each source's library lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartListsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val log = PlayLog(app, null)
    private val phoneSong = Track("media:1", "Portishead", "Roads", 240_000, uri = "content://media/external/audio/media/1")
    private val serverSong = Track("example:track:2", "Air", "La Femme d'Argent", 420_000, uri = "example:track:2")

    private fun smartLists(bookmarks: List<Track> = emptyList()) =
        SmartLists(
            plays = { log },
            phone = {
                IndexedPhoneLibrary({ false }, PhoneShelfReader(app.contentResolver), LibraryIndex(app, null), EnglishWords)
            },
            bookmarks = { bookmarks },
            words = EnglishWords,
            io = Dispatchers.Unconfined,
            clock = { 100L * 24 * 60 * 60 * 1000 },
        )

    @Test
    fun `a library lists only the songs its sources play`() =
        runTest {
            log.played(phoneSong, at = 1)
            log.played(serverSong, at = 2)
            log.played(serverSong, at = 3)

            val phoneOnly = smartLists().of(setOf(MusicSource.LOCAL))
            val together = smartLists().of(setOf(MusicSource.LOCAL, MusicSource.foreign("example")))

            assertEquals(listOf("MOST PLAYED", "RECENTLY PLAYED"), phoneOnly.map { it.name })
            assertEquals(listOf("Roads"), phoneOnly.first().entries.map { it.first.title })
            assertEquals(listOf("La Femme d'Argent", "Roads"), together.first().entries.map { it.first.title })
            assertEquals(
                "2 PLAYS",
                together
                    .first()
                    .entries
                    .first()
                    .second,
            )
        }

    @Test
    fun `favorites not played for sixty days are not played lately, and bookmarks are a list`() =
        runTest {
            log.played(phoneSong, at = 1)
            log.played(phoneSong, at = 2)

            val lists = smartLists(bookmarks = listOf(phoneSong)).of(setOf(MusicSource.LOCAL))

            assertEquals(listOf("MOST PLAYED", "RECENTLY PLAYED", "NOT PLAYED LATELY", "BOOKMARKS"), lists.map { it.name })
        }

    private object Shelf : BrowseSource {
        override val capabilities = BrowseCapabilities()
        override val available = true

        override suspend fun artists() = listOf(LibraryArtist("a1", "Autechre"))

        override suspend fun albums(artistId: String?) = listOf(LibraryAlbum("l1", "Amber", "Autechre"))

        override suspend fun tracks(albumId: String) = emptyList<Track>()
    }

    @Test
    fun `LISTS has the library's own lists above the saved ones, and a saved one still renames and deletes`() {
        val lists = PlaylistLibrary(temp.newFolder("playlists"))
        lists.save("Road trip", listOf(phoneSong))
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(emptyList(), scope))
        val smart = listOf(SmartList("MOST PLAYED", listOf(phoneSong to "3 PLAYS", serverSong to "1 PLAY")))
        val ops =
            LibraryOps(
                { Shelf },
                facade,
                state,
                scope,
                lists,
                StationStore(File(temp.newFolder("radio"), "stations.m3u")),
                io = Dispatchers.Unconfined,
                words = EnglishWords,
                smartLists = { smart },
            )
        ops.open()

        ops.switchCategory(LibraryOps.Category.LISTS)

        assertEquals(listOf("MOST PLAYED", "Road trip"), ops.rows.map { it.label })
        assertEquals(listOf("2 TRK", "1 TRK"), ops.rows.map { it.detail })
        assertNull("the library's own list is not the listener's to delete", ops.keptAt(0))
        assertEquals(LibraryOps.Kept.LIST, ops.keptAt(1))
        ops.promptDeleteRow(1)
        assertEquals("Delete Road trip?", state.prompt?.title)
        state.prompt = null

        ops.tapRow(0)
        assertEquals("MOST PLAYED", ops.header)
        assertEquals(listOf("1. Portishead - Roads", "2. Air - La Femme d'Argent"), ops.rows.map { it.label })
        assertEquals(listOf("3 PLAYS", "1 PLAY"), ops.rows.map { it.detail })
        ops.playSelection()
        assertEquals(Transport.Playing, facade.state.value.transport)
    }
}
