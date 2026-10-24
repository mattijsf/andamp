// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState

/**
 * A skin's popup menu as the app's menu: each command an entry, ticked and grayed as the skin
 * says, each submenu nested, each separator a divider. Labels read as the skin wrote them, so they
 * are not translated. A pick calls [onPick] with the command's id; Back or a tap outside calls
 * [onDismiss].
 */
internal fun SkinMenu.toAmpMenu(
    title: String,
    anchor: MenuAnchor?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
): AmpMenu = AmpMenu(title, ampItems(onPick), anchor, onDismiss)

/**
 * This menu with the entry called [label] grayed and unticked: a classic switch, such as Double
 * Size, that means nothing while a modern skin is worn.
 */
internal fun AmpMenu.grayedAt(label: String): AmpMenu =
    copy(
        items =
            items.map { item ->
                when {
                    item is AmpMenuItem.Action && item.label == label -> item.copy(enabled = false, checked = false)
                    else -> item
                }
            },
    )

private fun SkinMenu.ampItems(onPick: (Int) -> Unit): List<AmpMenuItem> =
    items.map { item ->
        when (item) {
            is SkinMenu.Command -> {
                AmpMenuItem.Action(
                    item.text,
                    enabled = !item.disabled,
                    checked = item.checked,
                ) { onPick(item.id) }
            }

            is SkinMenu.Submenu -> {
                AmpMenuItem.Submenu(item.text, item.menu.ampItems(onPick))
            }

            SkinMenu.Separator -> {
                AmpMenuItem.Divider
            }
        }
    }

/**
 * Shows [surface]'s popup menus as [state]'s menu, at [anchor], until cancelled. Only the player
 * surface holding the skin's turn runs it.
 *
 * Each menu gets one answer: the picked id, or 0 for Back, a tap outside, or the menu being taken
 * down by something else (the screen clearing its menus, another menu in its place). A menu that
 * arrives while another of the app's menus is up answers 0 and leaves that one up. When the skin's
 * menu closes first (its wait ran out) or this stops, the menu it showed is taken down.
 */
internal suspend fun showSkinMenus(
    surface: WalSurface,
    state: WinampState,
    title: String,
    anchor: () -> MenuAnchor?,
) {
    snapshotFlow { surface.menu }.collectLatest { menu ->
        if (menu == null) return@collectLatest
        if (state.activeMenu != null) {
            surface.answerMenu(menu, 0)
            return@collectLatest
        }
        var answered = false
        val answer = { id: Int ->
            if (!answered) {
                answered = true
                surface.answerMenu(menu, id)
            }
        }
        val shown = menu.toAmpMenu(title, anchor(), onPick = answer, onDismiss = { answer(0) })
        state.activeMenu = shown
        try {
            snapshotFlow { state.activeMenu === shown }.first { !it }
            answer(0)
        } finally {
            if (state.activeMenu === shown) state.activeMenu = null
        }
    }
}
