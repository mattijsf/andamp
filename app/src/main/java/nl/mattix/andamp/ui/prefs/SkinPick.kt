// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.state.SkinEntry

/**
 * A row that names a chosen skin and opens the list to choose from: Default, which is the
 * player's own skin, or one that is installed. A choice whose skin is no longer installed reads
 * as Default.
 *
 * The row is tagged `[tag].row`, Default `[tag].default` and each skin `[tag].` and its id.
 */
@Composable
internal fun SkinPickRow(
    skins: List<SkinEntry>,
    /** The id of the chosen skin, or null for Default. */
    chosen: String?,
    /** The title of the list. */
    heading: String,
    tag: String,
    onPick: (String?) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val worn = chosen?.let { id -> skins.firstOrNull { it.id == id } }
    // the name for following the skin the player wears
    val default = stringResource(R.string.prefs_skin_default)
    NavRow(
        title = worn?.name ?: default,
        summary = stringResource(if (worn == null) R.string.prefs_skin_own else R.string.prefs_skin_instead),
        tag = "$tag.row",
        onClick = { choosing = true },
    )
    if (!choosing) return
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(heading) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SkinChoice(default, selected = worn == null, tag = "$tag.default") {
                    onPick(null)
                    choosing = false
                }
                skins.forEach { entry ->
                    SkinChoice(entry.name, selected = entry.id == worn?.id, tag = "$tag.${entry.id}") {
                        onPick(entry.id)
                        choosing = false
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.prefs_skin_close)) } },
    )
}

/**
 * One skin in the list. The whole row is the radio, so a screen reader stops once and hears whether
 * it is selected.
 */
@Composable
private fun SkinChoice(
    name: String,
    selected: Boolean,
    tag: String,
    onPick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .testTag(tag)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}
