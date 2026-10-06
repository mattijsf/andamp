// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The playlist across a restart: edit the queue, build a second view model,
 * get the same queue back. Picked files still open because the store takes a
 * persisted uri permission for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistPersistenceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var store: PlaylistStore

    /**
     * The mock fixtures have no uri and a row without one is not stored, so
     * the fixtures here carry one.
     */
    private val playable =
        FakeTracks.tracks.mapIndexed { i, track -> track.copy(uri = "content://test/audio/$i") }

    @Before
    fun setUp() {
        File(app.filesDir, "winamp.m3u").delete()
        // no room: a grant nothing names is released at the next save, so the tests
        // about which grants are kept do not have to hold hundreds first
        store = PlaylistStore(app, room = 0)
    }

    private fun vm(): WinampViewModel =
        WinampViewModel(
            app,
            // as in production, the backend is constructed with the restored queue
            createBackend = { scope ->
                val restored = store.initial(playable)
                MockBackend(restored.tracks, scope, startIndex = restored.currentIndex)
            },
            presetStore = InMemoryEqPresetStore(),
            playlistStore = store,
        )

    // runs the ViewModel through the framework's own teardown, onCleared and all
    private fun clearLikeTheFramework(vm: WinampViewModel) {
        val holder = ViewModelStore()
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            }
        ViewModelProvider(holder, factory)[WinampViewModel::class.java]
        holder.clear()
    }

    /**
     * Robolectric's looper runs on a simulated clock, so the save debounce
     * elapses only when that clock is advanced.
     */
    private fun await(condition: () -> Boolean) {
        repeat(AWAIT_ROUNDS) {
            if (condition()) return
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(5)
        }
        assertTrue("the condition holds within the wait", condition())
    }

    @Test
    fun `a queue edit is stored and comes back on the next launch`() {
        val first = vm()
        await { first.state.playlist.isNotEmpty() }
        first.playlistOps.removeAll()

        await { store.load()?.tracks?.isEmpty() == true }
        assertEquals(emptyList<Track>(), first.state.playlist)

        // add something, then relaunch
        first.playlistFiles.addAudio(Uri.parse("content://media/external/audio/media/42"))
        await { first.state.playlist.size == 1 }
        await { store.load()?.tracks?.size == 1 }

        val second = vm()
        await { second.state.playlist.size == 1 }
        assertEquals(
            "content://media/external/audio/media/42",
            second.state.playlist
                .single()
                .uri,
        )
    }

    @Test
    fun `the current row is remembered and comes back selected`() {
        val first = vm()
        await { first.state.playlist.size > 2 }
        first.playTrack(2)
        await { store.load()?.currentIndex == 2 }

        val second = vm()

        await { second.state.currentIndex == 2 }
        assertEquals(
            "the restored row holds the same track",
            first.state.playlist[2].id,
            second.state.playlist[2].id,
        )
    }

    @Test
    fun `a playlist the user cleared stays cleared`() {
        val first = vm()
        await { first.state.playlist.isNotEmpty() }

        first.playlistOps.removeAll()
        await { store.load()?.tracks?.isEmpty() == true }

        // an empty stored list comes back empty; the fallback is only for no
        // stored file
        assertEquals(emptyList<Track>(), store.initial(playable).tracks)
        val second = vm()
        await { second.state.playlist.isEmpty() }
    }

    @Test
    fun `lasting access is dropped for tracks that leave the queue`() {
        val vm = vm()
        await { vm.state.playlist.isNotEmpty() }
        val kept = Uri.parse("content://media/external/audio/media/1")
        val dropped = Uri.parse("content://media/external/audio/media/2")

        vm.playlistFiles.addAudio(dropped)
        await { vm.state.playlist.any { it.uri == dropped.toString() } }
        vm.playlistFiles.addAudio(kept)
        await { vm.state.playlist.any { it.uri == kept.toString() } }
        vm.state.selectedRows = setOf(vm.state.playlist.indexOfFirst { it.uri == dropped.toString() })
        vm.playlistOps.removeSelected()

        await { app.contentResolver.persistedUriPermissions.none { it.uri == dropped } }
        assertTrue(
            "a queued track keeps its grant after the prune",
            app.contentResolver.persistedUriPermissions.any { it.uri == kept },
        )
    }

    @Test
    fun `an edit made just before teardown is still written`() {
        val vm = vm()
        await { vm.state.playlist.isNotEmpty() }
        vm.playlistOps.removeAll()
        // no idling: the debounce has not elapsed, so nothing is on disk yet
        assertNull("nothing is saved before the debounce elapses", store.load())

        clearLikeTheFramework(vm)

        assertEquals(emptyList<Track>(), store.load()!!.tracks)
    }

    @Test
    fun `a picked file's read grant is persisted`() {
        val vm = vm()
        await { vm.state.playlist.isNotEmpty() }
        val picked = Uri.parse("content://com.android.providers.media.documents/document/audio%3A42")

        vm.playlistFiles.addAudio(picked)
        await { vm.state.playlist.any { it.uri == picked.toString() } }

        val held = app.contentResolver.persistedUriPermissions.map { it.uri to it.isReadPermission }
        assertTrue(
            "the picked file holds a persisted read grant, held=$held",
            held.any { (uri, read) -> uri == picked && read },
        )
    }

    @Test
    fun `an unpersistable uri costs the add nothing`() {
        // an http uri is not a document, so there is no grant to take
        val vm = vm()
        await { vm.state.playlist.isNotEmpty() }
        val before = vm.state.playlist.size

        vm.playlistFiles.addAudio(Uri.parse("http://example.com/stream.mp3"))

        await { vm.state.playlist.size == before + 1 }
    }

    @Test
    fun `nothing stored means the player starts with what it shipped with`() {
        assertNull(store.load())
        assertEquals(playable, store.initial(playable).tracks)
    }

    @Test
    fun `a torn playlist file is ignored instead of taking launch down`() {
        File(app.filesDir, "winamp.m3u").writeBytes(ByteArray(64) { 0xFF.toByte() })

        assertEquals(playable, store.initial(playable).tracks)
    }

    @Test
    fun `the stored file is an m3u with a header, EXTINF lines and bare uris`() {
        val vm = vm()
        await { vm.state.playlist.isNotEmpty() }
        vm.playlistFiles.addAudio(Uri.parse("content://media/external/audio/media/7"))
        await { store.load()?.tracks?.any { it.uri?.endsWith("/7") == true } == true }

        val text = File(app.filesDir, "winamp.m3u").readText()
        assertTrue("the file starts with the M3U header", text.startsWith(PlaylistCodec.HEADER))
        assertTrue("the file holds an EXTINF line", text.lines().count { it.startsWith("#EXTINF:") } >= 1)
        // no uri may be commented out: another player has to see them as entries
        assertTrue(text.lines().any { it == "content://media/external/audio/media/7" })
    }

    @Test
    fun `a leftover temp file is not read in place of the playlist`() {
        val target = File(app.filesDir, "winamp.m3u")
        store.save(playable, 0)
        val good = target.readText()
        // a temp file left behind by an interrupted write must not be the one read
        File(app.filesDir, "winamp.m3u.tmp").writeText("half a play")

        assertEquals(good, target.readText())
        assertEquals(playable.size, store.load()!!.tracks.size)
    }

    @Test
    fun `an exported playlist loads back with its queue and cursor`() {
        val out = java.io.ByteArrayOutputStream()
        store.export(out, playable, currentIndex = 3)

        val back = store.import(out.toByteArray().inputStream())!!

        assertEquals(playable, back.tracks)
        assertEquals(3, back.currentIndex)
    }

    @Test
    fun `a picked file that is not a playlist is refused, not loaded`() {
        assertNull(store.import(ByteArray(2048) { 0xFF.toByte() }.inputStream()))
        assertNull("an empty file is refused", store.import(ByteArray(0).inputStream()))
    }

    @Test
    fun `an oversized pick is rejected instead of being read into memory`() {
        // every line is a track, so only the size ceiling can refuse it; a
        // file of comments would come back empty either way
        val line = "Music/a.mp3\n".toByteArray()
        val huge = ByteArray(PlaylistStore.MAX_PLAYLIST_BYTES + 1) { line[it % line.size] }

        assertNull(store.import(huge.inputStream()))
    }

    @Test
    fun `a folder's grant survives while its tracks are in the queue`() {
        // ADD > DIR takes one grant on the folder; the queue names the tracks
        // underneath it, and pruning must see that as the same permission
        val folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlbum")
        val track = Uri.parse("$folder/document/primary%3AMusic%2FAlbum%2Fone.mp3")
        store.rememberTree(folder)

        store.save(listOf(Track("one", "", "one.mp3", 1000, uri = track.toString())), 0)
        store.retainOnly(setOf(track.toString()))

        assertTrue(
            "the folder grant is kept while its tracks are queued",
            app.contentResolver.persistedUriPermissions.any { it.uri == folder },
        )
    }

    @Test
    fun `a folder's grant is let go once nothing from it is queued`() {
        val folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FGone")
        store.rememberTree(folder)

        store.retainOnly(setOf("content://media/external/audio/media/9"))

        assertTrue(
            "an unused folder grant is released",
            app.contentResolver.persistedUriPermissions.none { it.uri == folder },
        )
    }

    /**
     * A saved list names its tracks by the same uris the queue does. A folder those tracks
     * lie under has to stay readable after the queue has moved on to something else, or the
     * list cannot play until the folder is added again.
     */
    @Test
    fun `a folder a saved list plays from keeps its access after the queue moves on`() {
        val folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRoad")
        val unused = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FUnused")
        store.rememberTree(folder)
        store.rememberTree(unused)
        val lists = File(app.filesDir, "playlists").also { it.deleteRecursively() }
        lists.mkdirs()
        PlaylistLibrary(lists).save(
            "Road trip",
            listOf(Track("one", "", "one.mp3", 1000, uri = "$folder/document/primary%3AMusic%2FRoad%2Fone.mp3")),
        )
        val vm = vm()
        await { vm.state.playlist.isNotEmpty() }

        vm.playlistOps.removeAll()

        // the prune has run once the folder nothing names is gone
        await { app.contentResolver.persistedUriPermissions.none { it.uri == unused } }
        assertTrue(
            "the saved list's folder is still readable",
            app.contentResolver.persistedUriPermissions.any { it.uri == folder },
        )
    }

    @Test
    fun `while there is room no access is given up at all`() {
        val roomy = PlaylistStore(app, room = 3)
        val folders = (1..3).map { Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2F$it") }
        folders.forEach(roomy::rememberTree)

        roomy.retainOnly(emptySet())

        assertEquals(
            folders.toSet(),
            app.contentResolver.persistedUriPermissions
                .map { it.uri }
                .toSet(),
        )
    }

    @Test
    fun `past the room what nothing names is given up and the rest is kept`() {
        val roomy = PlaylistStore(app, room = 3)
        val folders = (1..4).map { Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2F$it") }
        folders.forEach(roomy::rememberTree)

        roomy.retainOnly(setOf("${folders[0]}/document/primary%3AMusic%2F1%2Fone.mp3"))

        assertEquals(
            setOf(folders[0]),
            app.contentResolver.persistedUriPermissions
                .map { it.uri }
                .toSet(),
        )
    }

    @Test
    fun `a bookmarked file keeps its access after it leaves the queue`() {
        val picked = Uri.parse("content://com.android.providers.media.documents/document/audio%3A77")
        val unused = Uri.parse("content://com.android.providers.media.documents/document/audio%3A78")
        store.remember(picked)
        store.remember(unused)
        val bookmarks = File(app.filesDir, "bookmarks.m3u").also { it.delete() }
        BookmarkStore(bookmarks).add(Track("b", "", "Kept", 1000, uri = picked.toString()))
        val vm = vm()
        await { vm.state.playlist.isNotEmpty() }

        vm.playlistOps.removeAll()

        await { app.contentResolver.persistedUriPermissions.none { it.uri == unused } }
        assertTrue(
            "the bookmark's file is still readable",
            app.contentResolver.persistedUriPermissions.any { it.uri == picked },
        )
    }

    private companion object {
        /** 100 rounds of 100ms of simulated time: ten seconds of debounce budget. */
        const val AWAIT_ROUNDS = 100
    }

    @Test
    fun `an add asked to play starts the added file, and a plain add leaves the cursor alone`() {
        val vm = vm()
        await { vm.state.playlist.isNotEmpty() }
        val was = vm.state.playlist.size

        vm.playlistFiles.addAudio(Uri.parse("content://media/external/audio/media/7"), thenPlay = true)

        await { vm.state.playlist.size == was + 1 }
        await { vm.state.currentIndex == was }
        assertEquals(nl.mattix.andamp.core.model.Transport.Playing, vm.state.transport)

        vm.playlistFiles.addAudio(Uri.parse("content://media/external/audio/media/8"))

        await { vm.state.playlist.size == was + 2 }
        assertEquals("an add leaves the cursor in place", was, vm.state.currentIndex)
    }

    @Test
    fun `Play file replaces the queue and plays it, where ADD FILE only appends`() {
        // ADD > FILE appends and leaves the cursor; Play file replaces the queue
        // with the picked file and plays it
        val vm = vm()
        await { vm.state.playlist.size > 1 }

        vm.playlistFiles.addAudio(Uri.parse("content://media/external/audio/media/7"))

        await { vm.state.playlist.size == playable.size + 1 }
        assertEquals("an add leaves the cursor in place", 0, vm.state.currentIndex)

        vm.playlistFiles.playAudio(Uri.parse("content://media/external/audio/media/8"))

        await { vm.state.playlist.size == 1 }
        assertEquals("the cursor is on the picked file", 0, vm.state.currentIndex)
        assertEquals(nl.mattix.andamp.core.model.Transport.Playing, vm.state.transport)
    }
}
