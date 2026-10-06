// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asking for a folder again: only for music that can be read neither through the folder's
 * grant nor through the phone's library, once when a list is read and each time a row is
 * pressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderAccessOpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val state = WinampState()
    private val store = PlaylistStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val facade = PlayerFacade(MockBackend(emptyList(), scope))

    private val road = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRoad"
    private val gym = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FGym"

    private fun row(
        folder: String,
        file: String,
    ) = Track("id-$file", "", file, 60_000, uri = "$folder/document/primary%3AMusic%2F$file")

    /** A library that lists the files at the uris in [listed]. */
    private class Library(
        app: Application,
        val listed: Set<String> = emptySet(),
    ) : MediaStoreAudio(app) {
        override fun findAt(documentUri: String): Uri? =
            if (documentUri in listed) Uri.parse("content://media/external/audio/media/1") else null
    }

    private fun ops(library: Library = Library(app)) =
        FolderAccessOps(state, facade, scope, store, ReadableUri(app, library), io = Dispatchers.Unconfined)

    @Test
    fun `a list whose folder can be read neither way asks for it, once`() =
        runBlocking {
            val ops = ops()
            val rows = listOf(row(road, "one.mp3"))

            ops.listRead(rows)

            assertEquals("Allow Music/Road again?", state.prompt?.title)

            state.prompt = null
            ops.listRead(rows)

            assertNull("reading the list again does not ask again", state.prompt)
        }

    @Test
    fun `a list the library can play from asks for nothing`() =
        runBlocking {
            val rows = listOf(row(road, "one.mp3"))

            ops(Library(app, listed = setOf(rows[0].uri!!))).listRead(rows)

            assertNull(state.prompt)
        }

    @Test
    fun `a list whose folder is still granted asks for nothing`() =
        runBlocking {
            store.rememberTree(Uri.parse(road))

            ops().listRead(listOf(row(road, "one.mp3")))

            assertNull(state.prompt)
        }

    @Test
    fun `reading a list changes none of its rows`() =
        runBlocking {
            val rows = listOf(row(road, "one.mp3"), row(gym, "two.mp3"))
            facade.setQueue(rows, 0)

            ops(Library(app, listed = setOf(rows[0].uri!!))).listRead(rows)

            assertEquals(rows, facade.state.value.queue)
        }

    @Test
    fun `a pressed row the library has is played, under its own address`() {
        val rows = listOf(row(road, "one.mp3"))
        facade.setQueue(rows, 0)

        assertTrue("the press is taken care of", ops(Library(app, listed = setOf(rows[0].uri!!))).pressed(rows[0]))

        assertNull(state.prompt)
        assertEquals(Transport.Playing, facade.state.value.transport)
        assertEquals("the row is not rewritten", rows, facade.state.value.queue)
    }

    @Test
    fun `a pressed row that can be read neither way asks for its folder and plays nothing yet`() {
        val rows = listOf(row(road, "one.mp3"))
        facade.setQueue(rows, 0)

        assertTrue(ops().pressed(rows[0]))

        assertEquals("Allow Music/Road again?", state.prompt?.title)
        assertEquals(Transport.Stopped, facade.state.value.transport)
    }

    @Test
    fun `a pressed row asks again after a not now`() {
        val rows = listOf(row(road, "one.mp3"))
        facade.setQueue(rows, 0)
        val ops = ops()
        ops.pressed(rows[0])
        state.prompt = null

        ops.pressed(rows[0])

        assertEquals("Allow Music/Road again?", state.prompt?.title)
    }

    @Test
    fun `a pressed row whose folder is granted is left to the player`() {
        store.rememberTree(Uri.parse(road))

        assertEquals(false, ops().pressed(row(road, "one.mp3")))
    }

    @Test
    fun `allowing hands the folder to the screen that opens the picker`() =
        runBlocking {
            ops().listRead(listOf(row(road, "one.mp3")))

            state.prompt?.onConfirm?.invoke()

            assertNull(state.prompt)
            assertEquals(road, state.folderAsk?.folder)
        }

    @Test
    fun `the folder picked again is readable again and what waited for it runs`() {
        var played = false

        ops().answered(FolderAsk(road) { played = true }, Uri.parse(road))

        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri.toString() == road })
        assertTrue(played)
    }

    @Test
    fun `another folder opens nothing, so it is not kept and the ask comes back`() {
        var played = false

        ops().answered(FolderAsk(road) { played = true }, Uri.parse(gym))

        assertTrue(app.contentResolver.persistedUriPermissions.none { it.uri.toString() == gym })
        assertEquals("Allow Music/Road again?", state.prompt?.title)
        assertEquals(false, played)
    }

    @Test
    fun `a picker closed without a pick changes nothing`() {
        var played = false

        ops().answered(FolderAsk(road) { played = true }, null)

        assertNull(state.prompt)
        assertEquals(false, played)
    }
}
