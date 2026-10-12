// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nl.mattix.andamp.R

/** Whether holding a small control opens a magnified view of it. */
data class TapAssistPrefs(
    /** Mirrors [nl.mattix.andamp.state.TapAssistStore]. */
    val enabled: Boolean = true,
    val onEnabled: (Boolean) -> Unit = {},
)

@Composable
fun TapAssistRow(prefs: TapAssistPrefs) {
    SwitchRow(
        title = stringResource(R.string.prefs_tap_assist_title),
        summary = stringResource(if (prefs.enabled) R.string.prefs_tap_assist_on else R.string.prefs_tap_assist_off),
        checked = prefs.enabled,
        tag = "prefs.tapassist.switch",
        onChange = prefs.onEnabled,
    )
}
