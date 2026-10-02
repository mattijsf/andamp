// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * Winamp's Preferences > Bookmarks, as data: a list with Open, Enqueue, Edit and Remove.
 * The ui layer draws it.
 */
data class BookmarkSheet(
    /** Read each time, so the dialog shows the list as it is after a Remove. */
    val entries: () -> List<PresetEntry>,
    /** Winamp's Open: this instead of what is queued. */
    val onOpen: (key: String) -> Unit,
    /** Winamp's Enqueue: after what is queued, which keeps playing. */
    val onEnqueue: (key: String) -> Unit,
    /** Winamp's Edit: the same bookmark under a name the listener typed. */
    val onRename: (key: String, name: String) -> Unit,
    val onRemove: (key: String) -> Unit,
) {
    fun nameOf(key: String): String = entries().firstOrNull { it.key == key }?.label.orEmpty()
}
