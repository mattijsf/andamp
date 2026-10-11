// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable
import nl.mattix.andamp.state.PlaylistFont
import nl.mattix.andamp.state.RowHeight

/** Preferences > Player: the player window's own switches, grouped by what they touch. */
@Composable
internal fun PlayerPage(sections: PrefsSections) {
    Section("Windows")
    OverlayRow(sections.overlay)
    ShadeRow(sections.shade)
    Section("Lists")
    PlaylistFontRow(sections.lists)
    RowHeightRow(sections.lists)
    Section("Touch")
    TapAssistRow(sections.tapAssist)
    // shown only on a phone that can take colors from its wallpaper, so the heading never stands
    // over nothing
    if (sections.palette.offered) {
        Section("Colors")
        PaletteRow(sections.palette)
    }
}

/** The Player row's line: which of its switches are on, or what the page holds when none are. */
internal fun PrefsSections.playerSummary(): String {
    val on =
        listOfNotNull(
            "Always on top".takeIf { overlay.wanted },
            "Windows stay open".takeIf { !shade.shadeEnabled },
            "Regular font in the playlist".takeIf { lists.font == PlaylistFont.REGULAR },
            when (lists.rowHeight) {
                RowHeight.CLASSIC -> null
                RowHeight.ROOMY -> "Roomy rows"
                RowHeight.LARGE -> "Large rows"
            },
            "Tap assist".takeIf { tapAssist.enabled },
            "Wallpaper colors".takeIf { palette.offered && palette.enabled },
        )
    return on.joinToString(" · ").ifEmpty {
        if (overlay.offered) "Always on top, windows, lists, touch and colors" else "Windows, lists, touch and colors"
    }
}
