// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nl.mattix.andamp.R

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
        title = stringResource(R.string.prefs_palette_title),
        summary = stringResource(if (prefs.enabled) R.string.prefs_palette_on else R.string.prefs_palette_off),
        checked = prefs.enabled,
        tag = "prefs.palette.switch",
        onChange = prefs.onEnabled,
    )
}
