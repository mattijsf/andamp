// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.R
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.WinampViewModel

/**
 * "Skin Settings...": opens the dialog with the options a worn modern skin's scripts list for
 * Winamp's Options menu (see [SkinSettingsDialog]). Left out with a classic skin worn, with a
 * modern one parked under a music source's skin, before the skin has loaded, and for a skin that
 * lists no options. Marked as needing the app.
 */
internal fun skinSettingsItem(vm: WinampViewModel): AmpMenuItem? {
    val look = vm.skinOps.wal ?: return null
    if (look.skinOptions.entries.isEmpty()) return null
    return AmpMenuItem.Action(vm.words.get(R.string.menu_skin_settings_open), needsTheApp = true) {
        vm.state.skinSettings = look
    }
}

/** One line of the Skin Settings dialog. */
internal sealed interface SettingRow {
    /** A group's name; [depth] 0 for a group at the top, 1 for one inside it, and so on. */
    data class Heading(
        val text: String,
        val depth: Int,
    ) : SettingRow

    /** An option to tick, inside [depth] groups. */
    data class Option(
        val toggle: SkinOptions.Toggle,
        val depth: Int,
    ) : SettingRow

    /** Where the skin put a separator between options. */
    data object Divider : SettingRow
}

/**
 * The dialog's title line under "Skin Settings" and its rows for [options]. A list that is one
 * group, as Winamp Modern's "Winamp Modern" holding all the others, is opened: that group's name
 * is the title line and its groups are the headings. Each group's rows follow its heading; a
 * divider shows only between options, never at the ends or twice.
 */
internal fun settingRows(options: SkinOptions): Pair<String?, List<SettingRow>> {
    var entries = options.entries
    var title: String? = null
    while (entries.size == 1 && entries[0] is SkinOptions.Submenu) {
        val only = entries[0] as SkinOptions.Submenu
        title = only.text
        entries = only.entries
    }
    val rows = ArrayList<SettingRow>()
    addRows(entries, 0, rows)
    return title to rows
}

private fun addRows(
    entries: List<SkinOptions.Entry>,
    depth: Int,
    rows: MutableList<SettingRow>,
) {
    val start = rows.size
    for (e in entries) {
        when (e) {
            is SkinOptions.Toggle -> {
                rows.add(SettingRow.Option(e, depth))
            }

            is SkinOptions.Submenu -> {
                rows.add(SettingRow.Heading(e.text, depth))
                addRows(e.entries, depth + 1, rows)
            }

            SkinOptions.Separator -> {
                if (rows.size > start && rows.last() != SettingRow.Divider) rows.add(SettingRow.Divider)
            }
        }
    }
    if (rows.size > start && rows.last() == SettingRow.Divider) rows.removeAt(rows.lastIndex)
}
