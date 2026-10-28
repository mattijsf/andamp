// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nl.mattix.andamp.R

/** Preferences > Player: the player window's own switches, grouped by what they touch; [onRows] opens Song rows. */
@Composable
internal fun PlayerPage(
    sections: PrefsSections,
    onRows: () -> Unit = {},
) {
    Section(stringResource(R.string.prefs_player_windows_section))
    Group {
        OverlayRow(sections.overlay)
        ShadeRow(sections.shade)
    }
    Section(stringResource(R.string.prefs_player_lists_section))
    Group {
        ListFontRow(sections.lists)
        RowHeightRow(sections.lists)
        RowFormatRow(onRows)
        if (sections.lists.marksOffered) SourceMarksRow(sections.lists)
    }
    Section(stringResource(R.string.prefs_player_touch_section))
    Group { TapAssistRow(sections.tapAssist) }
    // shown only on a phone that can take colors from its wallpaper, so the heading never stands
    // over nothing
    if (sections.palette.offered) {
        Section(stringResource(R.string.prefs_player_colors_section))
        Group { PaletteRow(sections.palette) }
    }
    ModernSkinsSection(sections.modern)
}

/**
 * What the page holds, for the Player row's line, by the sections this phone shows: Colors where
 * it can take them from the wallpaper, Skins where modern skins are offered.
 */
@StringRes
internal fun PrefsSections.playerSummary(): Int {
    val colors = palette.offered
    val skins = modern.offered
    return when {
        colors && skins -> R.string.prefs_player_summary_all_skins
        colors -> R.string.prefs_player_summary_all
        skins -> R.string.prefs_player_summary_no_colors_skins
        else -> R.string.prefs_player_summary_no_colors
    }
}
