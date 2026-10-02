// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
 * A page asked for and overtaken before it arrived.
 *
 * [LibraryOps.open] can be called again while a fetch is in flight: another
 * library is picked from the main menu, or an account signs in or out. The
 * late answer does not land over the page that replaced it and does not end
 * the loading of the page still on its way. A page's rows keep asking the
 * source that listed them; this guards against a source being asked to open
 * an id that another source handed out.
 */
class LibraryLoadTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A library whose artists arrive when the test completes [gate]. */
    private class Slow(
        private val names: List<String>,
        /** Whether [artists] ignores cancellation and answers anyway. */
        private val stubborn: Boolean = false,
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities()
        override val available = true

        /** Completed to let the artists through. */
        val gate = CompletableDeferred<Unit>()

        /** The artist ids this library was asked to open. */
        val opened = mutableListOf<String?>()

        override suspend fun artists(): List<LibraryArtist> {
            if (stubborn) {
                withContext(NonCancellable) { gate.await() }
            } else {
                gate.await()
            }
            return names.map { LibraryArtist("id-$it", it) }
        }

        override suspend fun albums(artistId: String?): List<LibraryAlbum> {
            opened += artistId
            return emptyList()
        }

        override suspend fun tracks(albumId: String) = emptyList<Track>()
    }

    private fun TestScope.ops(showing: () -> BrowseSource) =
        LibraryOps(
            showing,
            PlayerFacade(MockBackend(emptyList(), this)),
            WinampState(),
            this,
            PlaylistLibrary(temp.newFolder()),
            StationStore(File(temp.newFolder(), "stations.m3u")),
        )

    @Test
    fun `an answer from the library that was replaced never lands`() =
        runTest {
            val first = Slow(listOf("Autechre", "Aphex Twin"))
            val second = Slow(listOf("Boards of Canada"))
            var showing: BrowseSource = first
            val ops = ops { showing }
            ops.open()
            runCurrent()

            // another library is shown while the first is still answering
            showing = second
            ops.open()
            runCurrent()
            first.gate.complete(Unit)
            runCurrent()

            assertTrue("the page waits for the library being shown", ops.loading)
            assertEquals(emptyList<String>(), ops.rows.map { it.label })

            second.gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("Boards of Canada"), ops.rows.map { it.label })
            assertEquals("1 ARTIST", ops.status)
            assertFalse(ops.loading)
        }

    @Test
    fun `a replaced library's answer that ignores cancellation never lands`() =
        runTest {
            val first = Slow(listOf("Autechre", "Aphex Twin"), stubborn = true)
            val second = Slow(listOf("Boards of Canada")).apply { gate.complete(Unit) }
            var showing: BrowseSource = first
            val ops = ops { showing }
            ops.open()
            runCurrent()

            showing = second
            ops.open()
            runCurrent()
            first.gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("Boards of Canada"), ops.rows.map { it.label })
            assertEquals("1 ARTIST", ops.status)
            assertFalse(ops.loading)
        }

    @Test
    fun `a page goes on asking the library that listed it`() =
        runTest {
            val first = Slow(listOf("Autechre")).apply { gate.complete(Unit) }
            val second = Slow(listOf("Boards of Canada")).apply { gate.complete(Unit) }
            var showing: BrowseSource = first
            val ops = ops { showing }
            ops.open()
            runCurrent()

            showing = second
            ops.tapRow(0)
            runCurrent()

            assertEquals(listOf<String?>("id-Autechre"), first.opened)
            assertTrue("the second library is asked about no artist", second.opened.isEmpty())
        }
}
