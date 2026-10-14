// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * The pieces every Preferences page is built from, in the shape the phone's Settings app
 * draws them: a small colored label over a block of rows, the block rounded as one and the rows
 * inside it parted by a hairline of the page's background.
 */

/** The corner of a row inside a group; the group's corners come from the theme. */
private val ROW_CORNER = RoundedCornerShape(4.dp)

/** The gap between two rows of a group, which shows the page through. */
private val ROW_GAP = 2.dp

/** A section's name, over the group that holds its rows. */
@Composable
internal fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** A paragraph on the page, aligned with the text of the rows. */
@Composable
internal fun Note(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One block of rows. Each row draws a background of its own, through [prefsRow]. */
@Composable
internal fun Group(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        content = content,
    )
}

/** The look of one row in a [Group]: full width, its own corners, and a filled background. */
@Composable
internal fun Modifier.prefsRow(selected: Boolean = false): Modifier =
    fillMaxWidth()
        .clip(ROW_CORNER)
        .background(
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        )

/** The list item's own background is off; the row's comes from [prefsRow]. */
@Composable
internal fun rowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * A row that goes somewhere: a page of this screen, or out of the app. The whole row is the target.
 *
 * [route] is the page it opens, when it opens one; the row is marked as selected while that page
 * shows beside the list.
 */
@Composable
internal fun NavRow(
    title: String,
    summary: String,
    tag: String,
    onClick: () -> Unit,
    icon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    leading: ImageVector? = null,
    /** What leads the row when it is not an icon from the set; takes precedence over [leading]. */
    leadingContent: (@Composable () -> Unit)? = null,
    route: String? = null,
) {
    val selected = route != null && route == LocalShowingPage.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = leadingContent ?: leading?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Icon(icon, contentDescription = null) },
        colors = rowColors(),
        modifier =
            Modifier
                .prefsRow(selected)
                .clickable(onClick = onClick)
                .semantics { this.selected = selected }
                .testTag(tag),
    )
}

/** A switch with its name and what it does. The whole row flips it; the switch carries [tag]. */
@Composable
internal fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
    leading: ImageVector? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = leading?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag)) },
        colors = rowColors(),
        modifier = Modifier.prefsRow().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** One of an exclusive choice. The whole row picks it; [trailing] is for an action on the choice. */
@Composable
internal fun RadioRow(
    title: String,
    summary: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = trailing,
        colors = rowColors(),
        modifier =
            Modifier
                .prefsRow()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .testTag(tag),
    )
}

/** A name and its value, read only. */
@Composable
internal fun StatRow(
    label: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Text(value, style = MaterialTheme.typography.bodyLarge) },
        colors = rowColors(),
        modifier = Modifier.prefsRow(),
    )
}
