// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.R
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.LibraryOps
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.Words

/**
 * The long press on a library row, standing in for Winamp's right click.
 *
 * The same three verbs whatever the row is. There is nothing to remove or crop, because a library
 * row is not an entry in a queue. A list the app saved has Delete list as well, since the app
 * keeps its file.
 */
fun libraryRowMenu(
    ops: LibraryOps,
    row: Int,
    label: String,
    anchor: MenuAnchor,
    words: Words,
): AmpMenu =
    AmpMenu(
        title = label,
        anchor = anchor,
        items =
            listOf(
                AmpMenuItem.Action(words.get(R.string.menu_play)) { ops.playRow(row) },
                AmpMenuItem.Action(words.get(R.string.menu_enqueue)) { ops.enqueueRow(row) },
                AmpMenuItem.Action(words.get(R.string.menu_enqueue_next)) { ops.enqueueNextRow(row) },
            ) +
                if (ops.canDeleteRow(row)) {
                    listOf(
                        AmpMenuItem.Divider,
                        AmpMenuItem.Action(words.get(R.string.menu_delete_list)) { ops.promptDeleteRow(row) },
                    )
                } else {
                    emptyList()
                },
    )
