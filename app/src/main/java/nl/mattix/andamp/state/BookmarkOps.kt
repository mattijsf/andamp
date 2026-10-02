// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.Track
import kotlin.coroutines.CoroutineContext

/**
 * Winamp's bookmarks: the list, and add, remove, rename, Open and Enqueue.
 *
 * The list is held in memory, because a menu is built during composition and must not read
 * a file there. Open replaces the queue with the bookmark; Enqueue appends it and leaves
 * what is playing alone.
 */
class BookmarkOps(
    private val store: BookmarkStore,
    private val state: WinampState,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    /** Replaces the queue with these tracks, starting at the given index. */
    private val queue: (List<Track>, Int) -> Unit,
    private val append: (List<Track>) -> Unit,
) {
    /** Every bookmark, as the menus and the preferences page show them. */
    var bookmarks by mutableStateOf(emptyList<Track>())
        private set

    /** The message of the last failed attempt; the screen clears it. */
    var message by mutableStateOf<String?>(null)

    init {
        reload()
    }

    /** Bookmarks the current track. */
    fun addCurrent() {
        val playing = state.playlist.getOrNull(state.currentIndex)
        edit(
            failed =
                when {
                    playing == null -> "Nothing is playing"
                    playing.uri == null -> "This track has nowhere to come back to"
                    else -> "Already bookmarked"
                },
        ) { store.add(playing) }
    }

    /**
     * Bookmarks each of [tracks], for the playlist's "Bookmark item(s)". A track that cannot
     * be bookmarked is skipped, and the others are still added.
     */
    fun addAll(tracks: List<Track>) {
        edit(failed = "Nothing here can be bookmarked") {
            tracks.count { store.add(it) } > 0
        }
    }

    fun removeAt(at: Int) = edit(failed = null) { store.removeAt(at) }

    /** Removes every bookmark whose uri is in [places]; used by the bookmarks dialog. */
    fun removeWhere(places: Set<String>) {
        val doomed = bookmarks.withIndex().filter { it.value.uri in places }.map { it.index }
        edit(failed = null) { doomed.sortedDescending().all { store.removeAt(it) } }
    }

    fun rename(
        at: Int,
        name: String,
    ) = edit(failed = null) { store.rename(at, name) }

    /**
     * Winamp's Open: replaces the queue with the bookmark. This matches Winamp, where
     * Play > Bookmark > an entry replaces the playlist and Enqueue does not.
     */
    fun open(at: Int) {
        bookmarks.getOrNull(at)?.let { queue(listOf(it), 0) }
    }

    /** Winamp's Enqueue: appends the bookmark to the queue, which keeps playing. */
    fun enqueue(at: Int) {
        bookmarks.getOrNull(at)?.let { append(listOf(it)) }
    }

    /** Plays the bookmark whose uri is [place]. */
    fun openWhere(place: String) {
        bookmarks.firstOrNull { it.uri == place }?.let { queue(listOf(it), 0) }
    }

    /** Renames the bookmark whose uri is [place]. */
    fun renameWhere(
        place: String,
        name: String,
    ) {
        val at = bookmarks.indexOfFirst { it.uri == place }
        if (at >= 0) rename(at, name)
    }

    /** Enqueues the bookmarks whose uri is in [places], in the order they are kept. */
    fun enqueueWhere(places: Set<String>) {
        append(bookmarks.filter { it.uri in places })
    }

    private fun reload() {
        scope.launch { bookmarks = withContext(io) { store.list() } }
    }

    private fun edit(
        failed: String?,
        change: suspend () -> Boolean,
    ) {
        scope.launch {
            val done = withContext(io) { change() }
            if (done) bookmarks = withContext(io) { store.list() } else failed?.let { message = it }
        }
    }
}
