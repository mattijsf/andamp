// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** FOLDERS and GENRES in LISTS, and tracks picked together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val temp = TemporaryFolder()

    private val foil = Track("t1", "Autechre", "Foil", 6_000, uri = "content://1")
    private val montreal = Track("t2", "Autechre", "Montreal", 7_000, uri = "content://2")
    private val roads = Track("t3", "Portishead", "Roads", 8_000, uri = "content://3")

    private object Shelf : BrowseSource {
        override val capabilities = BrowseCapabilities()
        override val available = true

        override suspend fun artists() = listOf(LibraryArtist("a1", "Autechre"))

        override suspend fun albums(artistId: String?) = listOf(LibraryAlbum("l1", "Amber", "Autechre"))

        override suspend fun tracks(albumId: String) = emptyList<Track>()
    }

    private val index = LibraryIndex(ApplicationProvider.getApplicationContext<Application>(), null)

    /** The phone's music in two folders and two genres, from the library's own index. */
    private fun phone(): IndexedPhoneLibrary {
        fun file(
            id: Long,
            track: Track,
            folder: String,
            genre: String?,
        ) = PhoneFile(
            id,
            track.uri!!,
            track.title,
            track.artist,
            "Amber",
            null,
            1,
            id.toInt(),
            null,
            1994,
            1000,
            0,
            folder,
            genre,
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        val reader =
            object : PhoneShelfReader(app.contentResolver) {
                override fun stamp() = "stamp"

                override fun read() =
                    listOf(
                        file(1, foil, "Music/Autechre/", "Electronic"),
                        file(2, montreal, "Music/Autechre/", "electronic"),
                        file(3, roads, "Download/", "Trip-Hop"),
                    )
            }
        return IndexedPhoneLibrary({ true }, reader, index, EnglishWords, io = Dispatchers.Unconfined)
    }

    private val state = WinampState()

    private fun library(source: BrowseSource): Pair<LibraryOps, PlayerFacade> {
        val facade = PlayerFacade(MockBackend(emptyList(), scope))
        val ops =
            LibraryOps(
                { source },
                facade,
                state,
                scope,
                PlaylistLibrary(temp.newFolder("playlists")),
                StationStore(File(temp.newFolder("radio"), "stations.m3u")),
                io = Dispatchers.Unconfined,
                words = EnglishWords,
                infoOf = index::infoOf,
            )
        ops.open()
        return ops to facade
    }

    @Test
    fun `LISTS has the phone's folders and genres, each opening its tracks`() {
        val (ops, _) = library(phone())

        ops.switchCategory(LibraryOps.Category.LISTS)
        assertEquals(listOf("FOLDERS", "GENRES"), ops.rows.take(2).map { it.label })
        assertEquals(listOf("2", "2"), ops.rows.take(2).map { it.detail })

        ops.tapRow(0)
        assertEquals(listOf("Download", "Music/Autechre"), ops.rows.map { it.label })
        assertEquals(listOf("1 TRK", "2 TRK"), ops.rows.map { it.detail })
        ops.tapRow(1)
        assertEquals("Music/Autechre", ops.header)
        assertEquals(listOf("1. Autechre - Foil", "2. Autechre - Montreal"), ops.rows.map { it.label })

        ops.up()
        ops.up()
        ops.tapRow(1) // GENRES
        assertEquals("a genre's case does not make two of it", listOf("Electronic", "Trip-Hop"), ops.rows.map { it.label })
    }

    @Test
    fun `a library that knows no folders has no FOLDERS`() {
        val (ops, _) = library(Shelf)

        ops.switchCategory(LibraryOps.Category.LISTS)

        assertTrue(ops.rows.none { it.label == "FOLDERS" })
    }

    @Test
    fun `tracks picked together play and enqueue in page order, and the back gesture lets them go`() {
        val (ops, facade) = library(phone())
        ops.switchCategory(LibraryOps.Category.LISTS)
        ops.tapRow(0) // FOLDERS
        ops.tapRow(1) // Music/Autechre

        ops.choose(1)
        ops.tapRow(0)
        assertEquals(setOf(0, 1), ops.chosen)
        assertEquals("2 CHOSEN", ops.status)
        ops.tapRow(1)
        assertEquals("a tap on a picked row takes it out", setOf(0), ops.chosen)
        ops.tapRow(1)

        ops.playSelection()
        assertEquals(
            listOf("Foil", "Montreal"),
            facade.state.value.queue
                .map { it.title },
        )

        ops.back()
        assertEquals(emptySet<Int>(), ops.chosen)
        assertEquals("Music/Autechre", ops.header)
    }

    @Test
    fun `a list of songs is written in the row format, and again where it is when the format changes`() {
        val (ops, _) = library(phone())
        ops.switchCategory(LibraryOps.Category.LISTS)
        ops.tapRow(1) // GENRES
        ops.tapRow(0) // Electronic

        state.rowFormat = RowFormat.parse("%title% (%album%, %year%)")
        ops.restyle()

        assertEquals("Electronic", ops.header)
        assertEquals(listOf("1. Foil (Amber, 1994)", "2. Montreal (Amber, 1994)"), ops.rows.map { it.label })
        ops.up()
        assertEquals("the page above is still where it was", "GENRES", ops.header)
    }
}
