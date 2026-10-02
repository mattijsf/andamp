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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Keep windows open", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (kept) {
                    "Windows stay their full size. The small button beside the close button, " +
                        "and tapping a title bar twice, no longer fold them away."
                } else {
                    "A window can fold down to a thin bar. Switch this on if that keeps " +
                        "happening when you did not mean it to."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = kept,
            onCheckedChange = { prefs.onShadeEnabled(!it) },
            modifier = Modifier.testTag("prefs.shade.switch"),
        )
    }
}
