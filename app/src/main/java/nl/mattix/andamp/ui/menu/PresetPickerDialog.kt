// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.PresetEntry
import nl.mattix.andamp.state.PresetPicker

/**
 * Winamp's preset dialogs as a Material dialog: a titled list, several rows pickable for a delete
 * and one for a load, and a button that says what will happen. Skin art stops at the canvas edge
 * (see ENGINEERING.md), so the dialog is drawn in Material.
 */
@Composable
fun PresetPickerDialog(
    picker: PresetPicker,
    onDismiss: () -> Unit,
) {
    AmpDialog(onDismiss) { PresetPickerSheet(picker, onDismiss) }
}

/**
 * The dialog's contents, without the window around them, separate so a test can drive them: a text
 * field inside a Robolectric dialog window never finishes measuring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetPickerSheet(
    picker: PresetPicker,
    onDismiss: () -> Unit,
) {
    var query by remember(picker) { mutableStateOf("") }
    var selected by remember(picker) { mutableStateOf(setOf<String>()) }
    val rows = picker.matching(query)

    Surface(
        shape = RoundedCornerShape(CORNER.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = ELEVATION.dp,
        // a definite width: a dialog is not full-bleed on a tablet, and
        // text measured against an unbounded one lays out at infinity
        modifier =
            Modifier
                .widthIn(max = DIALOG_MAX.dp)
                .fillMaxWidth()
                .testTag(TAG),
    ) {
        Column(Modifier.padding(PADDING.dp)) {
            Text(
                picker.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
            // an empty list says so in the message below, so it gets no count line
            Text(
                if (picker.entries.isEmpty()) "" else picker.countLabel(selected.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = TIGHT.dp, bottom = GAP.dp),
            )
            if (picker.searchable) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    shape = RoundedCornerShape(FIELD_CORNER.dp),
                    label = { Text("Search") },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = GAP.dp)
                            .testTag("$TAG.search"),
                )
            }
            if (rows.isEmpty()) {
                Text(
                    if (picker.entries.isEmpty()) picker.emptyMessage else "Nothing matches \"${query.trim()}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = GAP.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
                    modifier = Modifier.heightIn(max = LIST_MAX.dp),
                ) {
                    items(rows, key = { it.key }) { entry ->
                        PresetRow(
                            entry = entry,
                            picked = entry.key in selected,
                            multiSelect = picker.multiSelect,
                        ) { picked ->
                            selected =
                                when {
                                    !picker.multiSelect -> if (picked) setOf(entry.key) else emptySet()
                                    picked -> selected + entry.key
                                    else -> selected - entry.key
                                }
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = GAP.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.padding(end = ROW_GAP.dp)) { Text("Cancel") }
                // the action is a filled button, in the error colors when it is destructive
                Button(
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(FIELD_CORNER.dp),
                    colors =
                        if (picker.destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    onClick = {
                        picker.onConfirm(picker.entries.map { it.key }.filter { it in selected })
                        onDismiss()
                    },
                    modifier = Modifier.testTag("$TAG.confirm"),
                ) { Text(picker.confirmLabelFor(selected.size)) }
            }
        }
    }
}

@Composable
private fun PresetRow(
    entry: PresetEntry,
    picked: Boolean,
    multiSelect: Boolean,
    onPicked: (Boolean) -> Unit,
) {
    // the picked row is a filled pill, and the fill animates
    val container by animateColorAsState(
        if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "row",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(container, RoundedCornerShape(ROW_CORNER.dp))
                .toggleable(value = picked, onValueChange = onPicked)
                .padding(horizontal = ROW_PADDING.dp, vertical = ROW_PADDING_V.dp)
                .testTag("$TAG.row.${entry.key}"),
    ) {
        Box(Modifier.padding(end = ROW_GAP.dp)) {
            if (multiSelect) {
                Checkbox(checked = picked, onCheckedChange = null)
            } else {
                RadioButton(selected = picked, onClick = null)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Text(entry.label, style = MaterialTheme.typography.bodyLarge)
            entry.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** So tests and the modal host name the same thing. */
const val PRESET_PICKER_TAG = "presetpicker"

private const val TAG = PRESET_PICKER_TAG
private const val CORNER = 28
private const val FIELD_CORNER = 24
private const val ROW_CORNER = 18
private const val ELEVATION = 3
private const val PADDING = 24
private const val GAP = 12
private const val ROW_GAP = 8
private const val ROW_PADDING = 14
private const val ROW_PADDING_V = 12
private const val TIGHT = 2
private const val LIST_MAX = 360
private const val DIALOG_MAX = 420
