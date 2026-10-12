// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable

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
        title = "Keep windows open",
        summary =
            if (kept) {
                "Windows stay their full size. The small button beside the close button, " +
                    "and tapping a title bar twice, no longer fold them away."
            } else {
                "A window can fold down to a thin bar. Switch this on if that keeps " +
                    "happening when you did not mean it to."
            },
        checked = kept,
        tag = "prefs.shade.switch",
        onChange = { prefs.onShadeEnabled(!it) },
    )
}
