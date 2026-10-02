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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Match wallpaper colors", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (prefs.enabled) {
                    "AndAmp Dark and AndAmp Light take their colors from your wallpaper, like the rest of the phone."
                } else {
                    "AndAmp Dark and AndAmp Light keep their own colors."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = prefs.enabled,
            onCheckedChange = prefs.onEnabled,
            modifier = Modifier.testTag("prefs.palette.switch"),
        )
    }
}
