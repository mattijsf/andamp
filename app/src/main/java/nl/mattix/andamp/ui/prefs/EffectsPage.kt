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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.PluginOps
import nl.mattix.andamp.state.Words

/** A one-line summary of the rack, for the page that links to it. */
internal fun DspOps.summary(words: Words): String {
    if (!available) return words.get(R.string.prefs_effects_unavailable_summary)
    val on = rack.slots.count { it.enabled }
    return if (on == 0) {
        words.plural(R.plurals.prefs_effects_summary_none_on, rack.slots.size)
    } else {
        words.plural(R.plurals.prefs_effects_summary_on, rack.slots.size, on)
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
                stringResource(R.string.prefs_effects_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // the page reads in the order the audio does: the fixed input stage, the
    // effects in the order the listener set, and the fixed output stage
    if (dsp.rack.slots.any { dsp.pinned(it.pluginId) }) {
        RackLabel(stringResource(R.string.prefs_effects_input_label), top = 8.dp)
        RackSection(dsp, snackbars, scope, input = true)
    }
    RackLabel(stringResource(R.string.prefs_effects_label), top = 20.dp)
    Text(
        stringResource(R.string.prefs_effects_order_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RackSection(dsp, snackbars, scope)
    RackLabel(stringResource(R.string.prefs_effects_output_label), top = 20.dp)
    LimiterCard(on = dsp.rack.limiter, onChange = dsp::setLimiter, peaks = peaks)
    // the link to the plug-ins sits at the end, where a new one will appear
    Spacer(Modifier.height(12.dp))
    Card(onClick = onManage, modifier = Modifier.fillMaxWidth().testTag("prefs.plugins.manage")) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.prefs_effects_manage_plugins)) },
            supportingContent = {
                Text(
                    if (plugins.entries.isEmpty()) {
                        stringResource(R.string.prefs_effects_none_added)
                    } else {
                        stringResource(R.string.prefs_effects_added_count, plugins.entries.size)
                    },
                )
            },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        )
    }
    MoreEffectsLink()
}
