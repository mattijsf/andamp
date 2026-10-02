// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import nl.mattix.andamp.state.BookmarkSheet

/**
 * Winamp's Preferences > Bookmarks: the list, with Open, Enqueue, Edit and Remove. Renaming happens
 * in this same dialog, not in a second one on top of it.
 */
@Composable
fun BookmarkSheetDialog(
    sheet: BookmarkSheet,
    onDismiss: () -> Unit,
) {
    AmpDialog(onDismiss) { BookmarkSheetBody(sheet, onDismiss) }
}

/** The dialog's contents, separate so a test can drive them. */
@Composable
fun BookmarkSheetBody(
    sheet: BookmarkSheet,
    onDismiss: () -> Unit,
) {
    val rows = sheet.entries()
    var picked by remember(sheet) { mutableStateOf<String?>(null) }
    var renaming by remember(sheet) { mutableStateOf<String?>(null) }
    var typed by remember(sheet) { mutableStateOf("") }

    if (picked != null && rows.none { it.key == picked }) picked = null
    Surface(
        shape = RoundedCornerShape(CORNER.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = ELEVATION.dp,
        modifier = Modifier.widthIn(max = DIALOG_MAX.dp).fillMaxWidth().testTag(TAG),
    ) {
        Column(Modifier.padding(PADDING.dp)) {
            Text(
                if (renaming == null) "Bookmarks" else "Rename bookmark",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = GAP.dp),
            )
            when {
                renaming != null -> {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        shape = RoundedCornerShape(FIELD_CORNER.dp),
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth().testTag("$TAG.name"),
                    )
                }

                rows.isEmpty() -> {
                    Text(
                        "You have not bookmarked anything yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
                        modifier = Modifier.heightIn(max = LIST_MAX.dp),
                    ) {
                        items(rows, key = { it.key }) { entry ->
                            Row(
                                label = entry.label,
                                chosen = entry.key == picked,
                                tag = "$TAG.row.${entry.key}",
                            ) { picked = entry.key }
                        }
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = GAP.dp),
            ) {
                if (renaming != null) {
                    TextButton(onClick = { renaming = null }, modifier = Modifier.testTag("$TAG.rename.cancel")) {
                        Text("Cancel")
                    }
                    Button(
                        enabled = typed.isNotBlank(),
                        shape = RoundedCornerShape(FIELD_CORNER.dp),
                        onClick = {
                            renaming?.let { sheet.onRename(it, typed.trim()) }
                            renaming = null
                        },
                        modifier = Modifier.testTag("$TAG.rename.save"),
                    ) { Text("Save") }
                } else {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("$TAG.close")) { Text("Close") }
                    Action("Edit", picked, "$TAG.edit") { key ->
                        typed = sheet.nameOf(key)
                        renaming = key
                    }
                    Action("Enqueue", picked, "$TAG.enqueue") {
                        sheet.onEnqueue(it)
                        onDismiss()
                    }
                    Action("Open", picked, "$TAG.open") {
                        sheet.onOpen(it)
                        onDismiss()
                    }
                    Button(
                        enabled = picked != null,
                        shape = RoundedCornerShape(FIELD_CORNER.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        onClick = {
                            picked?.let(sheet.onRemove)
                            picked = null
                        },
                        modifier = Modifier.testTag("$TAG.remove"),
                    ) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun Action(
    label: String,
    picked: String?,
    tag: String,
    onPick: (String) -> Unit,
) {
    TextButton(
        enabled = picked != null,
        onClick = { picked?.let(onPick) },
        modifier = Modifier.testTag(tag),
    ) { Text(label) }
}

@Composable
private fun Row(
    label: String,
    chosen: Boolean,
    tag: String,
    onChoose: () -> Unit,
) {
    Surface(
        color =
            if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(FIELD_CORNER.dp),
        modifier = Modifier.fillMaxWidth().selectable(chosen, onClick = onChoose).testTag(tag),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(ROW_PADDING.dp),
        ) {
            RadioButton(selected = chosen, onClick = null)
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = ROW_GAP.dp))
        }
    }
}

private const val TAG = "bookmarks"
private const val CORNER = 28
private const val FIELD_CORNER = 16
private const val ELEVATION = 6
private const val DIALOG_MAX = 420
private const val PADDING = 24
private const val GAP = 12
private const val ROW_GAP = 8
private const val ROW_PADDING = 12
private const val LIST_MAX = 320
