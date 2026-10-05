// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.Screen

/**
 * The player's menu, as it reads from a home screen.
 *
 * [mainMenu] builds it. This adds the widget's own entries at the top and grays out every entry
 * marked `needsTheApp`: the windows to tick, Always On Top, Double Size, the Skin Browser and
 * the whole Visualization branch. Everything else stays enabled: what acts on the player, the pickers and
 * dialogs this host can show, and Preferences and the museum, which open the app at that screen.
 */
fun widgetMenu(
    vm: WinampViewModel,
    onWidgetSettings: () -> Unit,
    /** The pickers this host owns: a skin, a track, a folder. */
    onPickSkin: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
    /** Opens the app, at a named screen or at the player when null. */
    onOpen: (String?) -> Unit,
    onExit: () -> Unit,
): AmpMenu {
    val app =
        mainMenu(
            vm,
            onOpenFile = onOpenFile,
            onOpenFolder = onOpenFolder,
            onPickSkin = onPickSkin,
            onExit = onExit,
            onPreferences = { onOpen(Screen.PREFERENCES) },
            onMuseum = { onOpen(Screen.MUSEUM) },
            // grayed on the widget, so never called
            onAlwaysOnTop = {},
        )
    return AmpMenu(
        "Andamp",
        listOf(
            // the widget's own entries, at the top
            AmpMenuItem.Action("Widget settings...", onClick = onWidgetSettings),
            AmpMenuItem.Action("Open Andamp") { onOpen(null) },
            AmpMenuItem.Divider,
        ) + app.items.map { it.forTheWidget() },
        // there is no button to anchor to: a widget does not learn where the finger landed
        MenuAnchor(WindowStore.MAIN, 0, 0, 0, 0),
    )
}

/** The same entry, grayed where it only means something inside the app. */
private fun AmpMenuItem.forTheWidget(): AmpMenuItem =
    when (this) {
        is AmpMenuItem.Action -> {
            if (needsTheApp) copy(enabled = false) else this
        }

        is AmpMenuItem.Submenu -> {
            if (needsTheApp) copy(enabled = false) else copy(items = items.map { it.forTheWidget() })
        }

        AmpMenuItem.Divider -> {
            this
        }
    }
