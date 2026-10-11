// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.runtime.Composable
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
    SwitchRow(
        title = "Independent volume",
        summary =
            if (own) {
                "The slider is Andamp's alone and the phone's volume stays put"
            } else {
                "The slider moves the phone's media volume, like the rocker"
            },
        checked = own,
        tag = "prefs.volume.switch",
        onChange = { prefs.onMode(if (it) VolumeMode.APP else VolumeMode.DEVICE) },
        leading = Icons.AutoMirrored.Outlined.VolumeUp,
    )
}
