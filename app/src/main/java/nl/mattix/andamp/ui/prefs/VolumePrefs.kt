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
import nl.mattix.andamp.core.model.VolumeMode

/**
 * Whose volume the player's slider moves. Shown only where the backend can attenuate its own
 * output; a remote backend's volume is not the app's to change.
 */
data class VolumePrefs(
    val mode: VolumeMode = VolumeMode.DEVICE,
    /** [nl.mattix.andamp.core.model.Capabilities.canAttenuate]; the row hides without it. */
    val offered: Boolean = false,
    val onMode: (VolumeMode) -> Unit = {},
)

@Composable
fun VolumeRow(prefs: VolumePrefs) {
    if (!prefs.offered) return
    val own = prefs.mode == VolumeMode.APP
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Independent volume", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (own) {
                    "The slider is Andamp's alone and the phone's volume stays put"
                } else {
                    "The slider moves the phone's media volume, like the rocker"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = own,
            onCheckedChange = { prefs.onMode(if (it) VolumeMode.APP else VolumeMode.DEVICE) },
            modifier = Modifier.testTag("prefs.volume.switch"),
        )
    }
}
