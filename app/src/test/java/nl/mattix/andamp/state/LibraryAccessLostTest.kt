// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import java.io.File

/**
 * Music access taken away while the library window is reading.
 *
 * A source whose permission has gone answers a read with nothing, so
 * [LibraryOps] asks [BrowseSource.available] again after the read and shows
 * the prompt for access in place of an empty page.
 */
class LibraryAccessLostTest {
    @get:Rule
    val temp = TemporaryFolder()

    /**
     * A full library whose access goes away during the read of [losing] -
     * "artists" or "albums" - and answers every other read as it would.
     */
    private class Revoked(
        private val losing: String,
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities()

        override var available = true
            private set

        private fun <T> read(
            which: String,
            rows: List<T>,
        ): List<T> {
            if (which != losing) return rows
            available = false
            return emptyList()
        }

        override suspend fun artists(): List<LibraryArtist> =
            read("artists", listOf(LibraryArtist("a1", "Autechre", albumCount = 1)))

        override suspend fun albums(artistId: String?): List<LibraryAlbum> =
            read("albums", listOf(LibraryAlbum("l1", "Amber", "Autechre")))

        override suspend fun tracks(albumId: String): List<Track> = emptyList()
    }

    private fun TestScope.ops(source: BrowseSource) =
        LibraryOps(
            { source },
            PlayerFacade(MockBackend(emptyList(), this)),
            WinampState(),
            this,
            PlaylistLibrary(temp.newFolder()),
            StationStore(File(temp.newFolder(), "stations.m3u")),
        )

    @Test
    fun `access lost during the artists read asks for access`() =
        runTest {
            val ops = ops(Revoked(losing = "artists"))

            ops.open()
            runCurrent()

            assertTrue("the page asks for access", ops.needsAccess)
            assertEquals(emptyList<LibraryOps.Row>(), ops.rows)
        }

    @Test
    fun `access lost during the albums read asks for access`() =
        runTest {
            val ops = ops(Revoked(losing = "albums"))
            ops.open()
            runCurrent()
            assertEquals("the artists are read while access holds", listOf("Autechre"), ops.rows.map { it.label })

            ops.switchCategory(LibraryOps.Category.ALBUMS)
            runCurrent()

            assertTrue(ops.needsAccess)
            assertEquals(emptyList<LibraryOps.Row>(), ops.rows)
        }
}
