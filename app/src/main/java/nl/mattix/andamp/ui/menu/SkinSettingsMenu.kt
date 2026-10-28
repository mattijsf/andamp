// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.R
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.wal.WalSurface

/**
 * "Skin Settings": the options a worn modern skin's scripts list for Winamp's Options menu, as the
 * skin names and nests them, ticked as the skin holds them; a pick flips one, the skin's scripts
 * hear it, and it is kept as the skin's other settings are. Left out with a classic skin worn, with
 * a modern one parked under a music source's skin, before the skin has loaded, and for a skin that
 * lists no options. Marked as needing the app.
 */
internal fun skinSettingsItem(vm: WinampViewModel): AmpMenuItem? {
    val look = vm.skinOps.wal ?: return null
    val entries = look.skinOptions.entries
    if (entries.isEmpty()) return null
    return AmpMenuItem.Submenu(vm.words.get(R.string.menu_skin_settings), skinOptionItems(entries, look), needsTheApp = true)
}

/** [entries] as menu items, each toggle flipping its option on [look]. */
private fun skinOptionItems(
    entries: List<SkinOptions.Entry>,
    look: WalSurface,
): List<AmpMenuItem> =
    entries.map { e ->
        when (e) {
            is SkinOptions.Toggle -> AmpMenuItem.Action(e.text, checked = e.checked) { look.toggleSkinOption(e.key) }
            is SkinOptions.Submenu -> AmpMenuItem.Submenu(e.text, skinOptionItems(e.entries, look))
            SkinOptions.Separator -> AmpMenuItem.Divider
        }
    }
