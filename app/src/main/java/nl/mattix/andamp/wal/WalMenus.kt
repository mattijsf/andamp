// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import nl.mattix.andamp.R
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinMenuChoices
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.Words

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

/**
 * The skin's right-click menus a long press offers under "Skin menu", for the press [seq]: the
 * menu of the object under it when the engine found one there ([WalLayout.menuTarget]); else each
 * object with a skin menu on screen ([WalLayout.menuChoices]), named by [skinMenuName], once per
 * name. Each entry right-clicks its object. Empty when the skin shows no right-click menu.
 */
internal fun skinMenusFor(
    surface: WalSurface,
    seq: Int,
    words: Words,
): List<AmpMenuItem.Action> {
    val layout = surface.layout ?: return emptyList()
    if (layout.menuTarget == seq) {
        return listOf(AmpMenuItem.Action(words.get(R.string.menu_skin_menu)) { surface.rightClick(seq) })
    }
    val choices = layout.menuChoices?.takeIf { it.seq == seq } ?: return emptyList()
    var unnamed = 0
    return choices.objects
        .mapIndexed { i, o -> (skinMenuName(o, words) ?: words.get(R.string.menu_skin_menu_numbered, ++unnamed)) to i }
        // the stars of a rating each have a menu, and the same name: one entry stands for them all
        .distinctBy { (name, _) -> name }
        .map { (name, i) -> AmpMenuItem.Action(name) { surface.rightClick(seq, i) } }
}

/**
 * What to call an object's right-click menu: the object's tooltip, as the skin wrote it, else what
 * a word of its id says it is (`main.vis.trigger` is a visualizer, `TimerTrigger` the time), else
 * null.
 */
private fun skinMenuName(
    choice: SkinMenuChoices.Choice,
    words: Words,
): String? {
    choice.tooltip
        ?.trim()
        ?.replace(SPACES, " ")
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    val known = choice.id.split(ID_BREAKS).firstNotNullOfOrNull { ID_WORDS[it.lowercase()] } ?: return null
    return words.get(known)
}

private val SPACES = Regex("\\s+")

/** Where an id breaks into words: at anything not a letter, and where lower case turns upper. */
private val ID_BREAKS = Regex("[^A-Za-z]+|(?<=[a-z])(?=[A-Z])")

/** The words skins use in the ids of objects with a right-click menu, and what each names. */
private val ID_WORDS =
    mapOf(
        "vis" to R.string.menu_skin_menu_visualizer,
        "visualizer" to R.string.menu_skin_menu_visualizer,
        "visualization" to R.string.menu_skin_menu_visualizer,
        "time" to R.string.menu_skin_menu_time,
        "timer" to R.string.menu_skin_menu_time,
        "albumart" to R.string.menu_skin_menu_album_art,
        "art" to R.string.menu_skin_menu_album_art,
        "cover" to R.string.menu_skin_menu_album_art,
        "rate" to R.string.menu_skin_menu_rating,
        "rating" to R.string.menu_skin_menu_rating,
    )
