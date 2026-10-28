// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import java.io.File

/**
 * The listener's bookmarks, kept as one .m3u file.
 *
 * A bookmark is a [Track], stored the way a station is ([PlaylistCodec]), so the file is an
 * .m3u another player can read. Plain JVM, like [StationStore] and [PlaylistLibrary], so
 * tests run against a temp file.
 */
class BookmarkStore(
    private val file: File,
    private val flush: Flush = forceToDisk,
) {
    /**
     * Every bookmark, oldest first. Rows whose uri has no scheme are dropped, since a
     * hand-edited or foreign .m3u can decode into rows that cannot be opened.
     */
    fun list(): List<Track> = read().orEmpty()

    /**
     * The uris the bookmarks name, so the access they need is kept; null when the file is
     * there and could not be read.
     */
    fun uris(): Set<String>? = read()?.mapNotNullTo(mutableSetOf()) { it.uri }

    /** What is stored, or null when the file is there and could not be read; see [readOrNull]. */
    private fun read(): List<Track>? =
        file
            .readOrNull()
            ?.let { text -> runCatching { PlaylistCodec.decode(text).tracks }.getOrNull() }
            ?.filter { it.uri?.let(::isSomewhere) == true }

    /**
     * Bookmarks [track]; false when it has no usable uri, when the same uri is already
     * bookmarked, or when the file could not be read or written.
     */
    fun add(track: Track?): Boolean = addAll(listOf(track)) > 0

    /**
     * Bookmarks each of [tracks] with one write, for the playlist's "Bookmark item(s)". A track
     * with no usable uri, or whose uri is already bookmarked or comes earlier in [tracks], is
     * skipped. Returns how many were added: 0 also when the file could not be read or written.
     */
    fun addAll(tracks: List<Track?>): Int {
        val kept = read() ?: return 0
        val taken = kept.mapNotNullTo(mutableSetOf()) { it.uri }
        val added =
            tracks.mapNotNull { track ->
                val where = track?.uri?.trim().orEmpty()
                if (isSomewhere(where) && taken.add(where)) track!!.copy(uri = where) else null
            }
        if (added.isEmpty() || !write(kept + added)) return 0
        return added.size
    }

    fun removeAt(at: Int): Boolean = removeAt(setOf(at))

    /**
     * Removes the bookmarks at each of [indices] with one write; false when none of them is a
     * bookmark, or when the file could not be read or written.
     */
    fun removeAt(indices: Set<Int>): Boolean {
        val kept = read() ?: return false
        if (indices.none { it in kept.indices }) return false
        return write(kept.filterIndexed { index, _ -> index !in indices })
    }

    /** Winamp's Edit: a bookmark keeps its place and takes a new name. */
    fun rename(
        at: Int,
        name: String,
    ): Boolean {
        val kept = read() ?: return false
        val called = name.trim()
        if (at !in kept.indices || called.isEmpty()) return false
        return write(kept.mapIndexed { index, track -> if (index == at) track.named(called) else track })
    }

    /** A renamed bookmark has an empty artist, so the playlist's "artist - title" shows the typed name alone. */
    private fun Track.named(what: String) = copy(artist = "", title = what)

    private fun write(bookmarks: List<Track>): Boolean =
        runCatching { file.writeAtomically(PlaylistCodec.encode(PlaylistCodec.Saved(bookmarks, 0)), flush) }.isSuccess

    private companion object {
        /** A place has a scheme: content://, file://, https://, asset:///. */
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        fun isSomewhere(uri: String) = SCHEME.containsMatchIn(uri)
    }
}
