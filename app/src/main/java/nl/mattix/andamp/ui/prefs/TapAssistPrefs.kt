// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Whether holding a small control opens a magnified view of it. */
data class TapAssistPrefs(
    /** Mirrors [nl.mattix.andamp.state.TapAssistStore]. */
    val enabled: Boolean = true,
    val onEnabled: (Boolean) -> Unit = {},
)

@Composable
fun TapAssistRow(prefs: TapAssistPrefs) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Tap assist", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (prefs.enabled) {
                    "Hold a small button and a magnified view opens above your finger. " +
                        "Slide to the one you meant and let go."
                } else {
                    "Buttons answer a plain tap only. Switch this on if the small ones are hard to hit."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = prefs.enabled,
            onCheckedChange = prefs.onEnabled,
            modifier = Modifier.testTag("prefs.tapassist.switch"),
        )
    }
}
