// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.core.model.TrackInfo

/**
 * Winamp's Alt+3, as a list of lines. It shows the lines the track's source provided, in the order
 * given, so the same dialog serves a tagged mp3 and a track that knows only its title.
 */
@Composable
fun TrackInfoDialog(
    info: TrackInfo,
    onDismiss: () -> Unit,
) {
    AmpDialog(onDismiss) { TrackInfoBody(info, onDismiss) }
}

/** The dialog's contents, separate so a test can drive them. */
@Composable
fun TrackInfoBody(
    info: TrackInfo,
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
                info.heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = GAP).testTag("info.heading"),
            )
            LazyColumn(Modifier.heightIn(max = LIST_MAX), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                items(info.lines) { line ->
                    Row(Modifier.fillMaxWidth().testTag("info.${line.label}")) {
                        Text(
                            line.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = LABEL_W, max = LABEL_W),
                        )
                        Text(
                            line.value,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

private val CORNER = 16.dp
private val ELEVATION = 6.dp
private val WIDTH = 380.dp
private val PAD = 20.dp
private val GAP = 12.dp
private val ROW_GAP = 6.dp
private val LABEL_W = 104.dp
private val LIST_MAX = 420.dp
