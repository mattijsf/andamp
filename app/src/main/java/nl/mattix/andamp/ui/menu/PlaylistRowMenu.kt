// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.R
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore

/**
 * Winamp's right click on a playlist row, opened by a long press.
 *
 * The wording and the order are Winamp's: the verbs act on the selection, which is why the labels
 * say "item(s)". File info shows the first selected track.
 */
fun playlistRowMenu(
    vm: WinampViewModel,
    row: Int,
    anchor: MenuAnchor,
): AmpMenu {
    val s = vm.state
    val chosen = s.selectedRows.sorted().ifEmpty { listOf(row) }
    val tracks = chosen.mapNotNull { s.playlist.getOrNull(it) }
    return AmpMenu(
        // named for what it acts on, the way the window's other menus are
        title = if (tracks.size > 1) vm.words.get(R.string.menu_n_items, tracks.size) else tracks.firstOrNull()?.title.orEmpty(),
        anchor = anchor,
        items =
            listOf(
                // Winamp's Enter: the first of what is selected starts playing
                AmpMenuItem.Action(vm.words.get(R.string.menu_play_items), enabled = tracks.isNotEmpty()) {
                    chosen.firstOrNull()?.let { vm.playTrack(it) }
                },
                AmpMenuItem.Action(vm.words.get(R.string.menu_remove_items), enabled = tracks.isNotEmpty()) {
                    vm.playlistOps.removeSelected()
                },
                AmpMenuItem.Action(vm.words.get(R.string.menu_crop_files), enabled = tracks.isNotEmpty()) {
                    vm.playlistOps.crop()
                },
                AmpMenuItem.Divider,
                AmpMenuItem.Action(vm.words.get(R.string.menu_file_info), enabled = tracks.isNotEmpty()) {
                    vm.trackInfoOps.show(tracks.firstOrNull())
                },
                AmpMenuItem.Action(vm.words.get(R.string.menu_bookmark_items), enabled = tracks.any { it.uri != null }) {
                    vm.bookmarkOps.addAll(tracks)
                },
            ),
    )
}

/** Where the menu springs from: the row the finger went down on. */
fun playlistRowAnchor(
    rowTop: Int,
    rowH: Int,
    left: Int,
    width: Int,
): MenuAnchor = MenuAnchor(WindowStore.PLAYLIST, left, rowTop, width, rowH)
