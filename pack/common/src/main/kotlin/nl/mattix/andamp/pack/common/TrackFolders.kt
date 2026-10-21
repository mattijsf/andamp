// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import nl.mattix.andamp.core.model.LibraryGroup

/**
 * The folders a library's tracks are in, found from the path of each track's file, for a
 * source whose server lists tracks with their paths but has no list of the folders that hold
 * them.
 *
 * A folder is named by its path from the folder above the top of the library, the way the
 * phone's folders read `Music/Artist/Album`. The top is the root that holds it, of the
 * [roots] given, or else the folder all of them share: tracks in `/srv/music/A/B` and
 * `/srv/music/C` give `music/A/B` and `music/C`. A root of `""` stands for paths that are
 * already relative to the top, which are then named as they are. Paths may use `/` or `\`.
 *
 * A folder's id is its path, as the server wrote it, so a later question about it
 * needs nothing else to find it.
 */
class TrackFolders private constructor(
    /** Every folder that holds a track, by name without case. */
    val folders: List<Folder>,
) {
    /** One folder: its path as the server wrote it (also its id), its name, and its tracks' ids in the order they came. */
    class Folder(
        val path: String,
        val name: String,
        val trackIds: List<String>,
    )

    private val byPath = folders.associateBy { it.path }

    /** The ids of the tracks in the folder [path]; empty for a folder this does not know. */
    fun trackIds(path: String): List<String> = byPath[path]?.trackIds.orEmpty()

    /** The folders as the player reads them. */
    fun groups(): List<LibraryGroup> = folders.map { LibraryGroup(it.path, it.name, it.trackIds.size) }

    companion object {
        /**
         * The folders of [tracks], each a track's id and the path of its file. A track with
         * no path, or a path with no folder in it, is left out.
         */
        fun of(
            tracks: List<Pair<String, String>>,
            roots: List<String>? = null,
        ): TrackFolders {
            val held = LinkedHashMap<String, MutableList<String>>()
            for ((id, file) in tracks) {
                val cut = file.lastIndexOfAny(charArrayOf('/', '\\'))
                if (cut <= 0) continue
                held.getOrPut(file.substring(0, cut)) { mutableListOf() } += id
            }
            val tops = roots?.map(::segments) ?: listOf(shared(held.keys.map(::segments)))
            val folders =
                held.map { (path, ids) ->
                    val parts = segments(path)
                    // the longest top that holds this folder; none leaves the whole path
                    val top = tops.filter { parts.startsWith(it) }.maxByOrNull { it.size }
                    val from = top?.let { (it.size - 1).coerceAtLeast(0) } ?: 0
                    Folder(path, parts.drop(from).joinToString("/").ifEmpty { path }, ids)
                }
            return TrackFolders(folders.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }))
        }

        /** A path's folder names, without the empty ones a leading, doubled or trailing separator leaves. */
        private fun segments(path: String): List<String> = path.split('/', '\\').filter { it.isNotEmpty() }

        /** The folder names every one of [paths] starts with. */
        private fun shared(paths: List<List<String>>): List<String> {
            val first = paths.firstOrNull() ?: return emptyList()
            val same = first.indices.takeWhile { at -> paths.all { it.getOrNull(at) == first[at] } }.size
            return first.take(same)
        }

        private fun List<String>.startsWith(top: List<String>): Boolean = size >= top.size && subList(0, top.size) == top
    }
}
