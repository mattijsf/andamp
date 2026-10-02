// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.ChoiceSheet

/**
 * Asks which of several places a verb applies to: a title, the places, and a line each saying what
 * the place means. A tap is the answer; there is no confirm button.
 */
@Composable
fun ChoiceSheetDialog(
    sheet: ChoiceSheet,
    onDismiss: () -> Unit,
) {
    AmpDialog(onDismiss) { ChoiceSheetBody(sheet, onDismiss) }
}

/** The dialog's contents, separate so a test can drive them. */
@Composable
fun ChoiceSheetBody(
    sheet: ChoiceSheet,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(CORNER),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = ELEVATION,
        modifier = Modifier.widthIn(max = WIDTH),
    ) {
        Column(Modifier.padding(PAD)) {
            Text(
                sheet.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = GAP),
            )
            sheet.options.forEach { option ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            // the sheet closes first: picking may raise a prompt or a picker of its
                            // own
                            onDismiss()
                            option.onPick()
                        }.testTag("choice.${option.label}")
                        .padding(vertical = ROW_PAD),
                ) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        option.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}

private val CORNER = 16.dp
private val ELEVATION = 6.dp
private val WIDTH = 360.dp
private val PAD = 20.dp
private val GAP = 8.dp
private val ROW_PAD = 12.dp
