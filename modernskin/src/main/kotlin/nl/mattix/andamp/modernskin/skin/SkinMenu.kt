// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * A popup menu a skin's script built and popped (`PopupMenu.popAtMouse`): what the host shows for
 * the listener to pick from. The script hears the picked command's id, or 0 for none.
 */
class SkinMenu(
    val items: List<Item>,
) {
    sealed interface Item

    /** A command the script tells apart by its [id]; [text] reads as the listener sees it. */
    class Command(
        val id: Int,
        val text: String,
        val checked: Boolean,
        val disabled: Boolean,
    ) : Item

    data object Separator : Item

    class Submenu(
        val text: String,
        val menu: SkinMenu,
    ) : Item
}
