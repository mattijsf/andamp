// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nl.mattix.andamp.R

/**
 * Whether windows may fold down to their title bar. The switch is worded as the cure for folding a
 * window by accident: on means windows stay open, so it shows the opposite of the stored value.
 */
data class ShadePrefs(
    /** Mirrors [nl.mattix.andamp.state.ShadeStore]; the switch shows its opposite. */
    val shadeEnabled: Boolean = true,
    val onShadeEnabled: (Boolean) -> Unit = {},
)

@Composable
fun ShadeRow(prefs: ShadePrefs) {
    val kept = !prefs.shadeEnabled
    SwitchRow(
        title = stringResource(R.string.prefs_shade_title),
        summary = stringResource(if (kept) R.string.prefs_shade_on else R.string.prefs_shade_off),
        checked = kept,
        tag = "prefs.shade.switch",
        onChange = { prefs.onShadeEnabled(!it) },
    )
}
