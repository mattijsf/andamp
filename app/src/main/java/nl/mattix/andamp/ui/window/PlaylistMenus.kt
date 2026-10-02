// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.skin.Sprite
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.WinampViewModel

/**
 * One of the five expanding bottom-bar menus. Entries pop upward from the button: the last
 * entry sits on the button itself, and the side bar is drawn at buttonX - 3. Geometry from
 * webamp's playlist-window.css: buttons are 22x18 at bottom:12.
 */
internal class PlaylistMenu(
    val id: String,
    val buttonX: Int,
    val bar: Sprite,
    val entries: List<PlaylistMenuEntry>,
) {
    /** Window-relative y of the topmost entry for a window of height [windowH]. */
    fun topY(windowH: Int): Int = windowH - MENU_BOTTOM_INSET - entries.size * ENTRY_H

    companion object {
        const val ENTRY_W = 22
        const val ENTRY_H = 18
        const val BAR_W = 3
        const val MENU_BOTTOM_INSET = 12
    }
}

/** The actions that need a document picker, which only the screen can launch. */
class PlaylistMenuActions(
    val addFile: () -> Unit = {},
    /** Adds the picked file and starts playing it; for Play on an empty queue. */
    val addFileThenPlay: () -> Unit = {},
    val addDir: () -> Unit = {},
    /** Play file... and Play directory...: the queue becomes what was picked. */
    val playFile: () -> Unit = {},
    val playDir: () -> Unit = {},
    val saveList: () -> Unit = {},
    val loadList: () -> Unit = {},
)

/** MISC > Sort list, a submenu of three. */
private fun sortMenu(vm: WinampViewModel) =
    nl.mattix.andamp.state.AmpMenu(
        "Sort list",
        listOf(
            nl.mattix.andamp.state.AmpMenuItem
                .Action("Sort list by title") { vm.playlistOps.sortByTitle() },
            nl.mattix.andamp.state.AmpMenuItem
                .Action("Reverse list") { vm.playlistOps.reverse() },
            nl.mattix.andamp.state.AmpMenuItem
                .Action("Randomize list") { vm.playlistOps.randomize() },
        ),
        nl.mattix.andamp.state
            .MenuAnchor(nl.mattix.andamp.state.WindowStore.PLAYLIST, MISC_X, MISC_MENU_Y, 8, 8),
    )

/** The anchor of the sort menu: the MISC button's x. */
private const val MISC_X = 101
private const val MISC_MENU_Y = 0

class PlaylistMenuEntry(
    val id: String,
    val normal: Sprite,
    val selected: Sprite,
    val action: (WinampViewModel) -> Unit,
)

internal fun playlistMenus(
    actions: PlaylistMenuActions = PlaylistMenuActions(),
    /** The window's width: LIST OPTS is anchored to the right edge. */
    width: Int = PL_W,
): List<PlaylistMenu> =
    listOf(
        PlaylistMenu(
            "pl.menu.add",
            buttonX = 14,
            bar = SpriteMap.PLAYLIST_ADD_MENU_BAR,
            entries =
                listOf(
                    PlaylistMenuEntry("pl.menu.add.url", SpriteMap.PLAYLIST_ADD_URL, SpriteMap.PLAYLIST_ADD_URL_SELECTED) {
                        it.playlistOps.promptAddUrl()
                    },
                    PlaylistMenuEntry("pl.menu.add.dir", SpriteMap.PLAYLIST_ADD_DIR, SpriteMap.PLAYLIST_ADD_DIR_SELECTED) {
                        actions.addDir()
                    },
                    PlaylistMenuEntry("pl.menu.add.file", SpriteMap.PLAYLIST_ADD_FILE, SpriteMap.PLAYLIST_ADD_FILE_SELECTED) {
                        actions.addFile()
                    },
                ),
        ),
        PlaylistMenu(
            "pl.menu.rem",
            buttonX = 43,
            bar = SpriteMap.PLAYLIST_REMOVE_MENU_BAR,
            // MISC, ALL, CROP, SELECTED, the order of webamp's RemoveMenu.tsx. The sprite
            // sheet stacks them the other way.
            entries =
                listOf(
                    PlaylistMenuEntry(
                        "pl.menu.rem.misc",
                        SpriteMap.PLAYLIST_REMOVE_MISC,
                        SpriteMap.PLAYLIST_REMOVE_MISC_SELECTED,
                        // Winamp's "Remove all dead files"
                    ) { it.playlistFiles.removeDeadFiles() },
                    PlaylistMenuEntry("pl.menu.rem.all", SpriteMap.PLAYLIST_REMOVE_ALL, SpriteMap.PLAYLIST_REMOVE_ALL_SELECTED) {
                        it.playlistOps.removeAll()
                    },
                    PlaylistMenuEntry("pl.menu.rem.crop", SpriteMap.PLAYLIST_CROP, SpriteMap.PLAYLIST_CROP_SELECTED) {
                        it.playlistOps.crop()
                    },
                    PlaylistMenuEntry(
                        "pl.menu.rem.selected",
                        SpriteMap.PLAYLIST_REMOVE_SELECTED,
                        SpriteMap.PLAYLIST_REMOVE_SELECTED_SELECTED,
                    ) { it.playlistOps.removeSelected() },
                ),
        ),
        PlaylistMenu(
            "pl.menu.sel",
            buttonX = 72,
            bar = SpriteMap.PLAYLIST_SELECT_MENU_BAR,
            entries =
                listOf(
                    PlaylistMenuEntry(
                        "pl.menu.sel.invert",
                        SpriteMap.PLAYLIST_INVERT_SELECTION,
                        SpriteMap.PLAYLIST_INVERT_SELECTION_SELECTED,
                    ) { it.playlistOps.invertSelection() },
                    PlaylistMenuEntry(
                        "pl.menu.sel.zero",
                        SpriteMap.PLAYLIST_SELECT_ZERO,
                        SpriteMap.PLAYLIST_SELECT_ZERO_SELECTED,
                    ) {
                        it.playlistOps.selectZero()
                    },
                    PlaylistMenuEntry("pl.menu.sel.all", SpriteMap.PLAYLIST_SELECT_ALL, SpriteMap.PLAYLIST_SELECT_ALL_SELECTED) {
                        it.playlistOps.selectAll()
                    },
                ),
        ),
        PlaylistMenu(
            "pl.menu.misc",
            buttonX = 101,
            bar = SpriteMap.PLAYLIST_MISC_MENU_BAR,
            entries =
                listOf(
                    // opens the sort submenu (webamp's SortContextMenu)
                    PlaylistMenuEntry(
                        "pl.menu.misc.sort",
                        SpriteMap.PLAYLIST_SORT_LIST,
                        SpriteMap.PLAYLIST_SORT_LIST_SELECTED,
                    ) { vm ->
                        vm.state.activeMenu = sortMenu(vm)
                    },
                    PlaylistMenuEntry(
                        "pl.menu.misc.info",
                        SpriteMap.PLAYLIST_FILE_INFO,
                        SpriteMap.PLAYLIST_FILE_INFO_SELECTED,
                    ) { vm -> vm.trackInfoOps.show(vm.trackInfoOps.trackFor(vm.state.currentIndex)) },
                    PlaylistMenuEntry(
                        "pl.menu.misc.opts",
                        SpriteMap.PLAYLIST_MISC_OPTIONS,
                        SpriteMap.PLAYLIST_MISC_OPTIONS_SELECTED,
                    ) {},
                ),
        ),
        PlaylistMenu(
            "pl.menu.list",
            buttonX = width - 44, // css: right 22, 22 wide
            bar = SpriteMap.PLAYLIST_LIST_BAR,
            entries =
                listOf(
                    PlaylistMenuEntry("pl.menu.list.new", SpriteMap.PLAYLIST_NEW_LIST, SpriteMap.PLAYLIST_NEW_LIST_SELECTED) {
                        it.playlistOps.removeAll()
                    },
                    PlaylistMenuEntry(
                        "pl.menu.list.save",
                        SpriteMap.PLAYLIST_SAVE_LIST,
                        SpriteMap.PLAYLIST_SAVE_LIST_SELECTED,
                    ) { actions.saveList() },
                    PlaylistMenuEntry(
                        "pl.menu.list.load",
                        SpriteMap.PLAYLIST_LOAD_LIST,
                        SpriteMap.PLAYLIST_LOAD_LIST_SELECTED,
                    ) { actions.loadList() },
                ),
        ),
    )
