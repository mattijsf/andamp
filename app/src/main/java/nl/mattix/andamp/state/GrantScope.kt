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

    /** True when a grant in [held] covers [uri]: one on that uri, or on a folder it lies under. */
    fun isCovered(
        uri: String,
        held: Collection<String>,
    ): Boolean = held.any { isNeeded(it, setOf(uri)) }

    /**
     * How many grants are held before any is given up, on Android version [sdk]. The
     * platform keeps 512 for an app from Android 11 and 128 before it. Three quarters of
     * that leaves room to pick more files before the platform starts dropping the oldest.
     */
    fun room(sdk: Int): Int = (if (sdk >= ANDROID_11) CAP_FROM_ANDROID_11 else CAP_BEFORE) * 3 / 4

    /**
     * The folder a row was added under: the tree a document uri from ADD > DIR lies in, or
     * null for a row that is not under a folder (a picked file, a song from the library,
     * a station).
     */
    fun folderOf(uri: String): String? {
        val tree = uri.indexOf(TREE)
        val document = uri.indexOf(DOCUMENT, startIndex = tree + TREE.length)
        return if (tree < 0 || document < 0) null else uri.substring(0, document)
    }

    /** The folders [uris] play from that no grant in [held] covers any more. */
    fun lostFolders(
        uris: Collection<String>,
        held: Collection<String>,
    ): Set<String> = uris.mapNotNullTo(mutableSetOf(), ::folderOf).apply { removeAll(held.toSet()) }

    /**
     * What a person would call [folder]: `Music/Albums` for
     * `.../tree/primary%3AMusic%2FAlbums`. The part before the colon names the storage, and
     * a folder that is a whole storage keeps it.
     */
    fun folderName(folder: String): String {
        val id = java.net.URLDecoder.decode(folder.substringAfter(TREE), "UTF-8")
        return id.substringAfter(':').ifEmpty { id.removeSuffix(":") }
    }

    private const val TREE = "/tree/"
    private const val DOCUMENT = "/document/"
    private const val ANDROID_11 = 30
    private const val CAP_FROM_ANDROID_11 = 512
    private const val CAP_BEFORE = 128
}
