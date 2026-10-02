// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.ParamSpec
import nl.mattix.andamp.core.model.RackSlot
import nl.mattix.andamp.core.model.UiNode
import nl.mattix.andamp.state.DspOps

/**
 * An effect's controls. An effect with no declared layout gets its parameters in the order it
 * declared them; one that declared a tree of [UiNode]s gets that.
 */
@Composable
internal fun Layout(
    spec: EffectSpec,
    slot: RackSlot,
    dsp: DspOps,
) {
    Presets(spec, slot, dsp)
    if (spec.ui.isEmpty()) {
        spec.params.forEach { Control(it, slot, dsp) }
        return
    }
    spec.ui.forEach { Node(it, spec, slot, dsp, enabled = true) }
}

@Composable
private fun Node(
    node: UiNode,
    spec: EffectSpec,
    slot: RackSlot,
    dsp: DspOps,
    enabled: Boolean,
) {
    when (node) {
        is UiNode.Label -> {
            Text(node.text, style = MaterialTheme.typography.bodySmall, color = textColour(enabled))
        }

        is UiNode.Control -> {
            spec.params.getOrNull(node.param)?.let { declared ->
                Control(declared.renamed(node), slot, dsp, enabled)
            }
        }

        is UiNode.Group -> {
            GroupCard(node, spec, slot, dsp, enabled)
        }
    }
}

/**
 * A titled card inside an effect's card, with its own switch when a toggle gates it. The switch
 * sits in the group's header because it decides whether the group's controls are heard. A gated-off
 * body is grayed, not hidden.
 */
@Composable
private fun GroupCard(
    group: UiNode.Group,
    spec: EffectSpec,
    slot: RackSlot,
    dsp: DspOps,
    enabled: Boolean,
) {
    val gate = group.enabledBy?.let { spec.params.getOrNull(it) }
    val on = gate == null || slot.params[gate.id, gate.default] >= HALF
    Spacer(Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("dsp.${spec.id}.group.${group.name ?: "untitled"}"),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (group.name != null || gate != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = textColour(enabled),
                        modifier = Modifier.weight(1f),
                    )
                    if (gate != null) {
                        Switch(
                            checked = on,
                            enabled = enabled,
                            onCheckedChange = { dsp.setValue(spec.id, gate.id, if (it) 1f else 0f) },
                            modifier = Modifier.testTag("dsp.${spec.id}.toggle.${gate.id}"),
                        )
                    }
                    group.help?.let {
                        Help(
                            ParamSpec(id = group.name.orEmpty(), name = group.name.orEmpty(), help = it),
                            spec.id,
                        )
                    }
                }
            }
            group.items.forEach { Node(it, spec, slot, dsp, enabled = enabled && on) }
        }
    }
}

/** The parameter with the name and help the layout gives it, when it overrides them. */
private fun ParamSpec.renamed(node: UiNode.Control) = copy(name = node.label ?: name, help = node.help ?: help)

@Composable
internal fun textColour(enabled: Boolean) =
    if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    }

/** Material's disabled opacity, which a bare Text does not apply by itself. */
private const val DISABLED_ALPHA = 0.38f

/**
 * An effect's presets, as chips above its controls. A preset need not mention every control, and
 * what it leaves out stays where the listener had it. A preset is applied in one edit, so its
 * values arrive together.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(
    spec: EffectSpec,
    slot: RackSlot,
    dsp: DspOps,
) {
    if (spec.presets.isEmpty()) return
    // wraps onto more lines: an effect may declare any number of presets, and one shared line would
    // break their names
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        spec.presets.forEach { preset ->
            // a filter chip, lit while the values match the preset; the match is re-derived, so
            // editing a slider unlights it
            FilterChip(
                selected = RackDigest.matches(spec, slot.params, preset),
                onClick = { dsp.apply(spec.id, preset.values) },
                label = { Text(preset.name, maxLines = 1) },
                modifier = Modifier.testTag("dsp.${spec.id}.preset.${preset.name.lowercase()}"),
            )
        }
    }
}
