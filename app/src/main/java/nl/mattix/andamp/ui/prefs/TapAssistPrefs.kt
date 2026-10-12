// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable

/** Whether holding a small control opens a magnified view of it. */
data class TapAssistPrefs(
    /** Mirrors [nl.mattix.andamp.state.TapAssistStore]. */
    val enabled: Boolean = true,
    val onEnabled: (Boolean) -> Unit = {},
)

@Composable
fun TapAssistRow(prefs: TapAssistPrefs) {
    SwitchRow(
        title = "Tap assist",
        summary =
            if (prefs.enabled) {
                "Hold a small button and a magnified view opens above your finger. " +
                    "Slide to the one you meant and let go."
            } else {
                "Buttons answer a plain tap only. Switch this on if the small ones are hard to hit."
            },
        checked = prefs.enabled,
        tag = "prefs.tapassist.switch",
        onChange = prefs.onEnabled,
    )
}
