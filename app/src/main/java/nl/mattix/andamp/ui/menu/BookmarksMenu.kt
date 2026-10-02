// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.core.model.displayName
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.BookmarkSheet
import nl.mattix.andamp.state.PresetEntry
import nl.mattix.andamp.state.WinampViewModel

/**
 * Winamp's Bookmarks menu, in Winamp's order: edit them, add what is playing, then the bookmarks
 * themselves. Adding takes no dialog, and tapping a bookmark plays it. Editing opens a
 * [BookmarkSheet], after Winamp's Preferences > Bookmarks.
 */
internal fun bookmarksMenu(vm: WinampViewModel): List<AmpMenuItem> {
    val ops = vm.bookmarkOps
    val playing = vm.state.playlist.getOrNull(vm.state.currentIndex)
    return buildList {
        add(
            // a sheet, so it also draws over a home screen
            AmpMenuItem.Action("Edit bookmarks...", enabled = ops.bookmarks.isNotEmpty()) {
                vm.state.bookmarkSheet =
                    BookmarkSheet(
                        entries = { ops.bookmarks.map { PresetEntry(it.uri.orEmpty(), it.displayName) } },
                        onOpen = { where -> ops.openWhere(where) },
                        onEnqueue = { where -> ops.enqueueWhere(setOf(where)) },
                        onRename = { where, name -> ops.renameWhere(where, name) },
                        onRemove = { where -> ops.removeWhere(setOf(where)) },
                    )
            },
        )
        // Winamp's Alt+I: no dialog and no name to type
        add(
            AmpMenuItem.Action("Add current as bookmark", enabled = playing?.uri != null) {
                ops.addCurrent()
            },
        )
        if (ops.bookmarks.isNotEmpty()) add(AmpMenuItem.Divider)
        addAll(bookmarkList(vm))
    }
}

/** The bookmarks alone, as listed under Play > Bookmark. Empty while nothing is bookmarked. */
internal fun bookmarkList(vm: WinampViewModel): List<AmpMenuItem> {
    val ops = vm.bookmarkOps
    return ops.bookmarks.mapIndexed { at, bookmark ->
        AmpMenuItem.Action(bookmark.displayName) { ops.open(at) }
    }
}
