// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.PluginOps

/** A one-line summary of the rack, for the page that links to it. */
internal fun DspOps.summary(): String {
    if (!available) return "This playback backend does not run effects."
    val on = rack.slots.count { it.enabled }
    val effects = if (rack.slots.size == 1) "effect" else "effects"
    return if (on == 0) {
        "${rack.slots.size} $effects · none on"
    } else {
        "${rack.slots.size} $effects · $on on"
    }
}

/** A section's name on the Effects page: Input, Effects, Output. */
@Composable
private fun RackLabel(
    text: String,
    top: androidx.compose.ui.unit.Dp,
) {
    Spacer(Modifier.height(top))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun DspRack(
    dsp: DspOps,
    plugins: PluginOps,
    snackbars: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    peaks: () -> nl.mattix.andamp.core.playback.PeakReading,
    onManage: () -> Unit,
) {
    if (!dsp.available) {
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This playback backend doesn't run effects.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // the page reads in the order the audio does: the fixed input stage, the
    // effects in the order the listener set, and the fixed output stage
    if (dsp.rack.slots.any { dsp.pinned(it.pluginId) }) {
        RackLabel("Input · before all effects", top = 8.dp)
        RackSection(dsp, snackbars, scope, input = true)
    }
    RackLabel("Effects", top = 20.dp)
    Text(
        "Audio flows top to bottom — the order changes the sound.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RackSection(dsp, snackbars, scope)
    RackLabel("Output · after all effects", top = 20.dp)
    LimiterCard(on = dsp.rack.limiter, onChange = dsp::setLimiter, peaks = peaks)
    // the link to the plug-ins sits at the end, where a new one will appear
    Spacer(Modifier.height(12.dp))
    Card(onClick = onManage, modifier = Modifier.fillMaxWidth().testTag("prefs.plugins.manage")) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
            headlineContent = { Text("Manage plug-ins") },
            supportingContent = {
                Text(if (plugins.entries.isEmpty()) "None added yet" else "${plugins.entries.size} added")
            },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        )
    }
    MoreEffectsLink()
}
