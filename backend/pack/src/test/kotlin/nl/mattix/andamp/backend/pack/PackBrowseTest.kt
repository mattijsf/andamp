// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackApi
import nl.mattix.andamp.core.packapi.PackArtist
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.PackTrack
import nl.mattix.andamp.core.playback.SourceUnreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A library read across a binder, a page at a time.
 *
 * The walk moves by the rows that arrived, ends at the page cap and on an empty page that
 * claims more, and tells a failed page apart from an empty shelf.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackBrowseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /**
     * The scope the client is given: unconfined on the test's scheduler, so that work the
     * constructor starts has run by the time a test looks.
     */
    private fun TestScope.own(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    /** A client on [own], with both dispatchers unconfined on the test's scheduler. */
    private fun TestScope.clientFor(): PackClient =
        PackClient(
            app,
            PackApi.ACTION_BIND,
            null,
            own(),
            UnconfinedTestDispatcher(testScheduler),
            UnconfinedTestDispatcher(testScheduler),
        )

    private fun shelf(
        from: Int,
        count: Int,
        more: Boolean,
    ): PackAnswer =
        PackAnswer(
            artists = (from until from + count).map { PackArtist(id = "artist:$it", name = "Artist $it") },
            more = more,
        )

    @Test
    fun `a library longer than one page is read to the end`() =
        runTest {
            val pages =
                mapOf(
                    0 to shelf(0, 3, more = true),
                    3 to shelf(3, 2, more = true),
                    5 to shelf(5, 1, more = false),
                )
            val pack = FakePack(answers = { pages[it.offset] ?: PackAnswer(failed = true) })
            app.install(pack)
            val browse = clientFor().browse()
            advanceUntilIdle()

            val artists = browse.artists()

            assertEquals(6, artists.size)
            assertEquals("Artist 5", artists.last().name)
        }

    /** The offset moves by the rows that came back, not by the page size asked for. */
    @Test
    fun `each page is asked for from where the last one ended`() =
        runTest {
            val pages =
                mapOf(
                    0 to shelf(0, 3, more = true),
                    3 to shelf(3, 2, more = false),
                )
            val pack = FakePack(answers = { pages[it.offset] ?: PackAnswer(failed = true) })
            app.install(pack)
            val browse = clientFor().browse()
            advanceUntilIdle()

            browse.artists()

            assertEquals(listOf(0, 3), pack.asked.filter { it.kind == PackQuestion.ARTISTS }.map { it.offset })
        }

    @Test
    fun `a page that fails part way keeps what arrived and clears whole`() =
        runTest {
            val pack = FakePack(answers = { if (it.offset == 0) shelf(0, 3, more = true) else PackAnswer(failed = true) })
            app.install(pack)
            val browse = clientFor().browse() as PackBrowse
            advanceUntilIdle()

            val artists = browse.artists()

            assertEquals("the rows that arrived are kept", 3, artists.size)
            assertFalse("a short read is not marked whole", browse.whole.value)
        }

    @Test
    fun `a question that fails on its first page throws SourceUnreachable`() =
        runTest {
            val pack = FakePack(answers = { PackAnswer(failed = true) })
            app.install(pack)
            val browse = clientFor().browse() as PackBrowse
            advanceUntilIdle()

            val thrown = runCatching { browse.artists() }.exceptionOrNull()

            assertTrue("a failed first page throws SourceUnreachable: $thrown", thrown is SourceUnreachable)
            assertFalse(browse.whole.value)
        }

    /** A search asks for one page, so one failed page fails the search. */
    @Test
    fun `a search the pack cannot answer throws SourceUnreachable`() =
        runTest {
            app.install(FakePack(answers = { PackAnswer(failed = true) }))
            val browse = clientFor().browse()
            advanceUntilIdle()

            assertTrue(runCatching { browse.search("dark", limit = 3) }.exceptionOrNull() is SourceUnreachable)
        }

    @Test
    fun `a failure after the second page returns the rows that came`() =
        runTest {
            val pages = mapOf(0 to shelf(0, 3, more = true), 3 to shelf(3, 3, more = true))
            val pack = FakePack(answers = { pages[it.offset] ?: PackAnswer(failed = true) })
            app.install(pack)
            val browse = clientFor().browse() as PackBrowse
            advanceUntilIdle()

            val artists = browse.artists()

            assertEquals(6, artists.size)
            assertEquals(listOf(0, 3, 6), pack.asked.filter { it.kind == PackQuestion.ARTISTS }.map { it.offset })
            assertFalse(browse.whole.value)
        }

    @Test
    fun `an empty shelf is read whole`() =
        runTest {
            val pack = FakePack(answers = { PackAnswer() })
            app.install(pack)
            val browse = clientFor().browse() as PackBrowse
            advanceUntilIdle()

            assertEquals(emptyList<Any>(), browse.artists())
            assertTrue("an empty answer is marked whole", browse.whole.value)
        }

    /** The walk ends at [PackBrowse.MAX_PAGES] even when the pack keeps claiming more. */
    @Test
    fun `a pack that never runs out is stopped at the cap`() =
        runTest {
            val pack = FakePack(answers = { shelf(it.offset, 1, more = true) })
            app.install(pack)
            val browse = clientFor().browse() as PackBrowse
            advanceUntilIdle()

            val artists = browse.artists()

            assertEquals(PackBrowse.MAX_PAGES, artists.size)
            assertEquals(PackBrowse.MAX_PAGES, pack.asked.count { it.kind == PackQuestion.ARTISTS })
            assertFalse(browse.whole.value)
        }

    /** Guards against walking forever on a page with no rows that claims there is more. */
    @Test
    fun `a page with no rows ends the walk`() =
        runTest {
            val pack = FakePack(answers = { PackAnswer(more = true) })
            app.install(pack)
            val browse = clientFor().browse()
            advanceUntilIdle()

            browse.artists()

            assertEquals(1, pack.asked.count { it.kind == PackQuestion.ARTISTS })
        }

    /** A search answers with its best matches first, so only one page is asked for. */
    @Test
    fun `a search asks once and honors its limit`() =
        runTest {
            val rows = (0 until 5).map { PackTrack("t$it", "Muse", "Song $it", 200_000, "example:track:$it") }
            val pack = FakePack(answers = { PackAnswer(tracks = rows, more = true) })
            app.install(pack)
            val browse = clientFor().browse()
            advanceUntilIdle()

            val hits = browse.search("dark", limit = 3)

            assertEquals(3, hits.size)
            assertEquals(1, pack.asked.count { it.kind == PackQuestion.SEARCH })
            assertEquals("dark", pack.asked.first { it.kind == PackQuestion.SEARCH }.query)
        }

    @Test
    fun `the browse capabilities come from the pack's descriptor`() =
        runTest {
            val pack = FakePack(descriptor = describes(canSearch = false, hasPlaylists = true, hasCatalogue = false))
            app.install(pack)
            val browse = clientFor().browse()
            advanceUntilIdle()

            assertEquals(
                BrowseCapabilities(hasArtists = true, hasAlbums = true, canSearch = false, hasPlaylists = true),
                browse.capabilities,
            )
        }

    @Test
    fun `a library nobody is signed into is not available`() =
        runTest {
            app.install(FakePack(whose = PackAccount(signedIn = false)))
            val browse = clientFor().browse()
            advanceUntilIdle()

            assertFalse(browse.available)
        }

    /** Asked anyway, it throws [SourceUnreachable]. */
    @Test
    fun `a library whose pack is not installed is not available`() =
        runTest {
            val browse = clientFor().browse()
            advanceUntilIdle()

            assertFalse(browse.available)
            assertTrue(runCatching { browse.artists() }.exceptionOrNull() is SourceUnreachable)
        }

    @Test
    fun `a signed-in pack's library is available`() =
        runTest {
            app.install(FakePack())
            val browse = clientFor().browse()
            advanceUntilIdle()

            assertTrue(browse.available)
        }
}
