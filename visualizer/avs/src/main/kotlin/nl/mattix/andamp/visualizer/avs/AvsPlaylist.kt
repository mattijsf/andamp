// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import java.io.File
import kotlin.random.Random

/**
 * Which preset plays, out of a directory of `.avs` files.
 *
 * State only, so a JVM test covers it: the view feeds it a directory and a
 * shuffle flag, asks for next/previous/goTo, and loads whatever [current]
 * answers. Shuffle permutes the order, so every preset appears once per cycle.
 *
 * There is no timer: the preset changes only when the listener asks.
 */
class AvsPlaylist(
    private val random: Random = Random.Default,
) {
    /** The files, sorted by name; empty until a directory is set. */
    var paths: List<String> = emptyList()
        private set

    private var order: List<Int> = emptyList()
    private var at = 0
    private var shuffled = false

    /** The path that should be playing, or null when the list is empty. */
    val current: String? get() = order.getOrNull(at)?.let { paths.getOrNull(it) }

    /** Index into [paths] of the current preset, or -1. */
    val position: Int get() = order.getOrNull(at) ?: -1

    /** Re-reads [directory]; keeps the current preset when it is still there. */
    fun setDirectory(
        directory: File?,
        shuffle: Boolean,
    ): Boolean {
        // the whole tree: a pack keeps the directory shape it was imported with
        val found =
            directory
                ?.takeIf { it.isDirectory }
                ?.walkTopDown()
                ?.filter { file -> file.isFile && file.extension.equals(EXTENSION, ignoreCase = true) }
                ?.map { it.absolutePath }
                ?.sortedBy { it.lowercase() }
                ?.toList()
                .orEmpty()
        if (found == paths && shuffle == shuffled) return false
        val playing = current
        paths = found
        shuffled = shuffle
        order = if (shuffle) paths.indices.shuffled(random) else paths.indices.toList()
        at = order.indexOfFirst { paths[it] == playing }.coerceAtLeast(0)
        return true
    }

    fun next() {
        if (order.isEmpty()) return
        at = (at + 1) % order.size
    }

    fun previous() {
        if (order.isEmpty()) return
        at = (at - 1 + order.size) % order.size
    }

    /** Jumps to [index] into [paths], the list the UI shows, whatever the shuffled order is. */
    fun goTo(index: Int) {
        val target = order.indexOf(index)
        if (target >= 0) at = target
    }

    /**
     * Jumps to [path] if it is in the list. A rebuilt render thread calls this
     * with the preset that was playing; otherwise a restarted loop under
     * shuffle starts at the first entry of a new random order.
     */
    fun goTo(path: String): Boolean {
        val index = paths.indexOf(path)
        if (index >= 0) goTo(index)
        return index >= 0
    }

    private companion object {
        const val EXTENSION = "avs"
    }
}

/**
 * Keeps the render loop from walking the disk every frame.
 *
 * The view's directory and shuffle fields are read every frame; the directory
 * walk happens only when one changed. The [File] is compared by identity, so a
 * new instance with the same path (the active pack imported again under the
 * same name) is walked again.
 */
internal class PlaylistSync(
    private val playlist: AvsPlaylist,
    private val onPlaylistChanged: (List<String>) -> Unit,
) {
    private var appliedDirectory: File? = null
    private var appliedShuffle: Boolean? = null

    fun apply(
        directory: File?,
        shuffle: Boolean,
    ) {
        if (directory === appliedDirectory && shuffle == appliedShuffle) return
        appliedDirectory = directory
        appliedShuffle = shuffle
        if (playlist.setDirectory(directory, shuffle)) {
            onPlaylistChanged(playlist.paths.map { File(it).nameWithoutExtension })
        }
    }
}
