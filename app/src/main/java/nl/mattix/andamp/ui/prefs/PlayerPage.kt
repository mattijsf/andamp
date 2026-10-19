// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nl.mattix.andamp.R
import nl.mattix.andamp.state.ListFont
import nl.mattix.andamp.state.RowFormat
import nl.mattix.andamp.state.RowHeight
import nl.mattix.andamp.state.Words

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
        RowFormatRow(sections.lists, onRows)
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
}

/** The Player row's line: which of its switches are on, or what the page holds when none are. */
internal fun PrefsSections.playerSummary(words: Words): String {
    val on =
        listOfNotNull(
            R.string.prefs_player_summary_always_on_top.takeIf { overlay.wanted },
            R.string.prefs_player_summary_windows_stay_open.takeIf { !shade.shadeEnabled },
            R.string.prefs_player_summary_regular_font.takeIf { lists.font == ListFont.REGULAR },
            when (lists.rowHeight) {
                RowHeight.CLASSIC -> null
                RowHeight.ROOMY -> R.string.prefs_player_summary_roomy_rows
                RowHeight.LARGE -> R.string.prefs_player_summary_large_rows
            },
            R.string.prefs_player_summary_row_format.takeIf { lists.rowFormat != RowFormat.DEFAULT },
            R.string.prefs_player_summary_tap_assist.takeIf { tapAssist.enabled },
            R.string.prefs_player_summary_wallpaper_colors.takeIf { palette.offered && palette.enabled },
        )
    return on.joinToString(" · ") { words.get(it) }.ifEmpty {
        words.get(if (overlay.offered) R.string.prefs_player_summary_all_with_overlay else R.string.prefs_player_summary_all)
    }
}
