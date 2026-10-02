// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * Which persisted permissions the playlist still needs.
 *
 * A folder picked with ADD > DIR is granted once, on the tree, while the queue names each
 * track by a document uri underneath it. A tree grant therefore counts as needed when any
 * track's uri lies under it.
 */
object GrantScope {
    /**
     * True when [grant] is still needed for [used]: a track is that uri, or lies under it.
     * The match includes the trailing separator, so a grant on `.../Songs` does not cover
     * `.../SongsOld/track.mp3`.
     */
    fun isNeeded(
        grant: String,
        used: Set<String>,
    ): Boolean = used.any { it == grant || it.startsWith("$grant/") }
}
