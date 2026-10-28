// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.wal.WalSurface

/**
 * The options a worn modern skin lists for Winamp's Options menu, on one page: each group under a
 * heading (see [settingRows]), each option a box ticked as the skin holds it. A tap flips it, the
 * skin's scripts hear it, and it is kept as the skin's other settings are. The ticks follow the
 * skin, so an option a script changes in answer shows changed.
 */
@Composable
internal fun SkinSettingsDialog(
    look: WalSurface,
    onDismiss: () -> Unit,
) {
    AmpDialog(onDismiss) { SkinSettingsBody(look.skinOptions, look::toggleSkinOption, onDismiss) }
}

/** The dialog's contents, separate so a test can drive them. */
@Composable
fun SkinSettingsBody(
    options: SkinOptions,
    onToggle: (SkinOptions.Key) -> Unit,
    onDismiss: () -> Unit,
) {
    val (subtitle, rows) = settingRows(options)
    Surface(
        shape = RoundedCornerShape(CORNER),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = ELEVATION,
        modifier = Modifier.widthIn(max = WIDTH),
    ) {
        Column(Modifier.padding(PAD)) {
            Text(
                stringResource(R.string.menu_skin_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings.subtitle"),
                )
            }
            // no taller than the screen leaves for it: the button under it stays in reach
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = LIST_MAX).padding(top = GAP)) {
                itemsIndexed(rows) { i, row -> SettingLine(row, first = i == 0, onToggle) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.menu_close))
            }
        }
    }
}

@Composable
private fun SettingLine(
    row: SettingRow,
    first: Boolean,
    onToggle: (SkinOptions.Key) -> Unit,
) {
    when (row) {
        is SettingRow.Heading -> {
            Text(
                row.text,
                style = if (row.depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(start = INDENT * row.depth, top = if (first) 0.dp else HEADING_TOP, bottom = HEADING_BOTTOM)
                        .testTag("settings.heading.${row.text}"),
            )
        }

        is SettingRow.Option -> {
            val t = row.toggle
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_MIN)
                    .toggleable(value = t.checked, role = Role.Checkbox) { onToggle(t.key) }
                    .padding(start = INDENT * row.depth)
                    .testTag("settings.option.${t.text}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // the row takes the tap and says what it is; the box only shows it
                Checkbox(checked = t.checked, onCheckedChange = null, modifier = Modifier.padding(end = BOX_GAP))
                Text(t.text, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SettingRow.Divider -> {
            HorizontalDivider(Modifier.padding(vertical = DIVIDER_PAD))
        }
    }
}

private val CORNER = 16.dp
private val ELEVATION = 6.dp
private val WIDTH = 400.dp
private val PAD = 20.dp
private val GAP = 12.dp
private val LIST_MAX = 480.dp
private val INDENT = 16.dp
private val HEADING_TOP = 16.dp
private val HEADING_BOTTOM = 4.dp
private val ROW_MIN = 44.dp
private val BOX_GAP = 12.dp
private val DIVIDER_PAD = 4.dp
