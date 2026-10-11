// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable

/**
 * Whether AndAmp Dark and Light take their colors from the wallpaper. The row is shown only where
 * the phone has a palette to give, which is Android 12 and later.
 */
data class PalettePrefs(
    /** Mirrors [nl.mattix.andamp.state.PaletteStore]. */
    val enabled: Boolean = true,
    /** Whether the phone has a palette to give; the row hides without it. */
    val offered: Boolean = false,
    val onEnabled: (Boolean) -> Unit = {},
)

@Composable
fun PaletteRow(prefs: PalettePrefs) {
    if (!prefs.offered) return
    SwitchRow(
        title = "Match wallpaper colors",
        summary =
            if (prefs.enabled) {
                "AndAmp Dark and AndAmp Light take their colors from your wallpaper, like the rest of the phone."
            } else {
                "AndAmp Dark and AndAmp Light keep their own colors."
            },
        checked = prefs.enabled,
        tag = "prefs.palette.switch",
        onChange = prefs.onEnabled,
    )
}
