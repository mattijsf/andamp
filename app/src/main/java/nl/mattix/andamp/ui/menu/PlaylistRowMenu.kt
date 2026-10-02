// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

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
        title = if (tracks.size > 1) "${tracks.size} items" else tracks.firstOrNull()?.title.orEmpty(),
        anchor = anchor,
        items =
            listOf(
                // Winamp's Enter: the first of what is selected starts playing
                AmpMenuItem.Action("Play item(s)", enabled = tracks.isNotEmpty()) {
                    chosen.firstOrNull()?.let { vm.playTrack(it) }
                },
                AmpMenuItem.Action("Remove item(s)", enabled = tracks.isNotEmpty()) {
                    vm.playlistOps.removeSelected()
                },
                AmpMenuItem.Action("Crop files", enabled = tracks.isNotEmpty()) {
                    vm.playlistOps.crop()
                },
                AmpMenuItem.Divider,
                AmpMenuItem.Action("File info", enabled = tracks.isNotEmpty()) {
                    vm.trackInfoOps.show(tracks.firstOrNull())
                },
                AmpMenuItem.Action("Bookmark item(s)", enabled = tracks.any { it.uri != null }) {
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
