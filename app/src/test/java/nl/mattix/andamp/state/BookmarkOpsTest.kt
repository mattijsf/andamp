// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [BookmarkOps]: adding, removing, and Winamp's Open and Enqueue. Open replaces the queue
 * with the bookmark; Enqueue appends it and leaves what is playing alone.
 */
class BookmarkOpsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val state = WinampState()
    private var queued = listOf<Track>()
    private var startedAt = -1
    private var appended = listOf<Track>()

    private fun track(
        at: Int,
        uri: String? = "content://songs/$at",
    ) = Track("id$at", "Artist", "Track $at", 200_000, uri = uri)

    private fun ops(): BookmarkOps =
        BookmarkOps(
            BookmarkStore(File(folder.root, "bookmarks.m3u")),
            state,
            scope,
            io = Dispatchers.Unconfined,
            queue = { tracks, from ->
                queued = tracks
                startedAt = from
            },
            append = { appended = it },
        )

    @Test
    fun `bookmarking keeps whatever is playing`() {
        state.playlist = listOf(track(1), track(2))
        state.currentIndex = 1
        val ops = ops()

        ops.addCurrent()

        assertEquals(listOf("Track 2"), ops.bookmarks.map { it.title })
    }

    @Test
    fun `a track without a uri is not bookmarked and a message says so`() {
        state.playlist = listOf(track(1, uri = null))
        state.currentIndex = 0
        val ops = ops()

        ops.addCurrent()

        assertEquals(emptyList<Track>(), ops.bookmarks)
        assertEquals("This track has nowhere to come back to", ops.message)
    }

    @Test
    fun `bookmarking with nothing playing says so`() {
        // the state starts with the fake queue, so an empty one has to be asked for
        state.playlist = emptyList()
        val ops = ops()

        ops.addCurrent()

        assertEquals("Nothing is playing", ops.message)
    }

    @Test
    fun `bookmarking the same place twice says so`() {
        state.playlist = listOf(track(1))
        state.currentIndex = 0
        val ops = ops()
        ops.addCurrent()

        ops.addCurrent()

        assertEquals(1, ops.bookmarks.size)
        assertEquals("Already bookmarked", ops.message)
    }

    @Test
    fun `open replaces the queue with the bookmark`() {
        state.playlist = listOf(track(1), track(2))
        state.currentIndex = 1
        val ops = ops()
        ops.addCurrent()

        ops.open(0)

        assertEquals(listOf("content://songs/2"), queued.map { it.uri })
        assertEquals(0, startedAt)
        assertEquals(emptyList<Track>(), appended)
    }

    @Test
    fun `enqueue appends the bookmark and leaves the queue alone`() {
        state.playlist = listOf(track(1))
        state.currentIndex = 0
        val ops = ops()
        ops.addCurrent()

        ops.enqueue(0)

        assertEquals(listOf("content://songs/1"), appended.map { it.uri })
        assertEquals("an enqueue does not replace the queue", emptyList<Track>(), queued)
    }

    @Test
    fun `opening a bookmark that is not there does nothing`() {
        val ops = ops()

        ops.open(3)
        ops.enqueue(-1)

        assertEquals(emptyList<Track>(), queued)
        assertEquals(emptyList<Track>(), appended)
    }

    @Test
    fun `enqueueing from the picker adds the ones chosen, in the order they are kept`() {
        state.playlist = listOf(track(1), track(2), track(3))
        val ops = ops()
        for (at in 0..2) {
            state.currentIndex = at
            ops.addCurrent()
        }

        ops.enqueueWhere(setOf("content://songs/3", "content://songs/1"))

        assertEquals(listOf("content://songs/1", "content://songs/3"), appended.map { it.uri })
        assertEquals("an enqueue does not replace the queue", emptyList<Track>(), queued)
    }

    @Test
    fun `removing takes the ones the picker handed back, and only those`() {
        state.playlist = listOf(track(1), track(2), track(3))
        val ops = ops()
        for (at in 0..2) {
            state.currentIndex = at
            ops.addCurrent()
        }

        ops.removeWhere(setOf("content://songs/1", "content://songs/3"))

        assertEquals(listOf("Track 2"), ops.bookmarks.map { it.title })
    }

    @Test
    fun `what is bookmarked outlives the ops object`() {
        state.playlist = listOf(track(1))
        state.currentIndex = 0
        ops().addCurrent()

        assertEquals(listOf("Track 1"), ops().bookmarks.map { it.title })
    }

    @Test
    fun `a bookmark that is added leaves no message`() {
        state.playlist = listOf(track(1))
        state.currentIndex = 0
        val ops = ops()

        ops.addCurrent()

        assertNull(ops.message)
    }
}
