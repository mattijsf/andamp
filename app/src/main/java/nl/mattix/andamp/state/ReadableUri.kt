// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context

/**
 * Which uri a local file is opened under right now.
 *
 * A row keeps the address it was added under for good. For a file from the phone's storage
 * or from a card that address says where the file is, so it still names the right file on
 * another phone and after a reinstall. Whether it opens is another matter: it needs a grant,
 * and a grant can be gone.
 *
 * So the uri to open is worked out each time a file is opened, by everything that opens
 * one: the player, the tags, the file info, the check for dead files.
 *
 * 1. The row's own uri, while a grant covers it.
 * 2. The library's uri for the file at the same place, which needs only the audio
 *    permission ([MediaStoreAudio.findAt]).
 * 3. The row's own uri again when neither works. Opening it fails, and the folder is asked
 *    for ([FolderAccessOps]).
 *
 * The answer is for one read and is never written into a row, a playlist or a bookmark: a
 * library uri is a number in this phone's library and means nothing anywhere else.
 */
class ReadableUri(
    /** The uris Andamp holds a persisted grant on. */
    private val held: () -> List<String>,
    private val library: MediaStoreAudio,
) {
    constructor(context: Context, library: MediaStoreAudio = MediaStoreAudio(context)) : this(
        { context.contentResolver.persistedUriPermissions.map { it.uri.toString() } },
        library,
    )

    /** The uri to open [uri] under; [uri] itself for anything but a storage document without its grant. */
    fun of(uri: String): String =
        if (!DocumentFilePath.covers(uri) || granted(uri)) uri else library.findAt(uri)?.toString() ?: uri

    /** Whether a grant covers [uri]: one on the document itself, or on a folder it lies under. */
    fun granted(uri: String): Boolean = GrantScope.isCovered(uri, held())

    /** The folders [uris] play from that no grant covers. Asks only what is held, so it is quick. */
    fun lostFolders(uris: Collection<String>): Set<String> = GrantScope.lostFolders(uris, held())

    /**
     * The folders [uris] play from that can be read neither way: no grant covers them, and
     * the library does not list their music. These are the ones to ask the listener for.
     *
     * One row stands for its folder, the first under it, so a list of a thousand rows costs
     * a lookup for each lost folder and not for each row.
     */
    fun unreached(uris: Collection<String>): Set<String> =
        lostFolders(uris).filterTo(mutableSetOf()) { folder ->
            library.findAt(uris.first { GrantScope.folderOf(it) == folder }) == null
        }
}
