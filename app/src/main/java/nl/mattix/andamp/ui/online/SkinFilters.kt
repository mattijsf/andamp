// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.online.OnlineSkins
import nl.mattix.andamp.state.online.SkinOrder

/** The museum's filter sheet: the order of the list, and whether to show only installed skins. */
@Composable
internal fun SkinFilters(online: OnlineSkins) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        // it lies over the list, so opening it does not move what the listener was looking at. The
        // sheet consumes every pointer event on it, so a drag across it does not scroll the grid
        // underneath
        shadowElevation = LIFT.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }.testTag("$TAG.filters"),
    ) {
        Column(Modifier.padding(GAP.dp), verticalArrangement = Arrangement.spacedBy(TIGHT.dp)) {
            Text("Order", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(TIGHT.dp)) {
                Sort(online, SkinOrder.MUSEUM, "Museum")
                Sort(online, SkinOrder.SHUFFLED, "Shuffle")
            }
            HorizontalDivider(Modifier.padding(vertical = TIGHT.dp))
            Toggle("Only skins you have", online.onlyInstalled, "$TAG.f.installed") {
                online.installedOnly(it)
            }
        }
    }
}

@Composable
private fun Sort(
    online: OnlineSkins,
    order: SkinOrder,
    label: String,
) {
    FilterChip(
        selected = online.order == order,
        enabled = online.canSort(order),
        onClick = { online.sortBy(order) },
        label = { Text(label) },
        modifier = Modifier.testTag("$TAG.sort.${order.name.lowercase()}"),
    )
}

@Composable
private fun Toggle(
    label: String,
    on: Boolean,
    tag: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Switch(checked = on, enabled = enabled, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

private const val TIGHT = 8

/** How far the sheet floats above the list it covers. */
private const val LIFT = 6
