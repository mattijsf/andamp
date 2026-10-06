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

    /**
     * How many grants are held before any is given up, on Android version [sdk]. The
     * platform keeps 512 for an app from Android 11 and 128 before it. Three quarters of
     * that leaves room to pick more files before the platform starts dropping the oldest.
     */
    fun room(sdk: Int): Int = (if (sdk >= ANDROID_11) CAP_FROM_ANDROID_11 else CAP_BEFORE) * 3 / 4

    private const val ANDROID_11 = 30
    private const val CAP_FROM_ANDROID_11 = 512
    private const val CAP_BEFORE = 128
}
