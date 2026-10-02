// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.ui.menu.shortLabel
import nl.mattix.andamp.widget.WidgetRefresh
import nl.mattix.andamp.widget.WidgetSettings
import nl.mattix.andamp.widget.WidgetSize

/**
 * The home screen widget's page: its visualizer and refresh rate, its size, what it takes presses
 * on, and what it cannot do.
 *
 * A widget cannot tell whether anyone is looking at it, so how much it spends on the visualizer and
 * the refresh is the listener's choice. The page ends with a list of limits that are Android's.
 */
data class WidgetPrefs(
    val settings: WidgetSettings = WidgetSettings(),
    val onSettings: (WidgetSettings) -> Unit = {},
)

/** The summary on the row that opens the page. */
fun WidgetPrefs.summary(): String {
    val picture = settings.mode.shortLabel().lowercase()
    return if (settings.mode == VisMode.Off) {
        "No visualizer, ${settings.refresh.label.lowercase()} refresh rate"
    } else {
        "$picture, ${settings.refresh.label.lowercase()} refresh rate"
    }
}

/**
 * What each visualizer mode is, in a sentence. The names come from [shortLabel], which the player's
 * Visualization menu uses too.
 */
private val VisMode.summary: String
    get() =
        when (this) {
            VisMode.Analyzer -> "Bars that move with the music, on a short loop"
            VisMode.Oscilloscope -> "A wavy line that moves with the music, on a short loop"
            VisMode.Off -> "Nothing moves, and nothing is spent on it"
        }

/**
 * The widget page's rows, in a Column of their own: a caller that does not stack its children would
 * draw them on top of each other, and a covered row receives no touches.
 */
@Composable
fun WidgetRows(prefs: WidgetPrefs) {
    Column {
        // Off is one of the modes, as in VisMode and the player's own visualizer menu, so there is
        // no separate switch
        Choice(
            title = "Visualizer",
            summary = prefs.settings.mode.summary,
            // in VisMode's own order
            options = VisMode.entries.map { it to it.shortLabel() },
            selected = prefs.settings.mode,
            tag = "prefs.widget.vis",
            onPick = { prefs.onSettings(prefs.settings.copy(mode = it)) },
        )

        // the refresh rate drives the clock as well as the visualizer, so it stays offered with the
        // visualizer off, with a summary about the time only
        Choice(
            title = "Refresh rate",
            summary =
                if (prefs.settings.mode == VisMode.Off) {
                    prefs.settings.refresh.timeOnly
                } else {
                    prefs.settings.refresh.summary
                },
            options = WidgetRefresh.entries.map { it to it.label },
            selected = prefs.settings.refresh,
            tag = "prefs.widget.refresh",
            onPick = { prefs.onSettings(prefs.settings.copy(refresh = it)) },
        )

        // a choice, because a cell is almost never a whole number of players wide: either the art
        // stops short of the edge or it leaves the whole-pixel grid. The trimmed corners are not
        // part of this choice; Android trims them either way
        Choice(
            title = "Size",
            summary = prefs.settings.size.summary,
            options = WidgetSize.entries.map { it to it.label },
            selected = prefs.settings.size,
            tag = "prefs.widget.size",
            onPick = { prefs.onSettings(prefs.settings.copy(size = it)) },
        )

        Toggle(
            title = "Only the logo opens Andamp",
            summary = "Tapping the player does nothing, so a near miss on a button stays on the home screen.",
            checked = prefs.settings.openOnLogo,
            tag = "prefs.widget.logo",
            onChange = { prefs.onSettings(prefs.settings.copy(openOnLogo = it)) },
        )

        Toggle(
            title = "Volume control",
            summary = "Lets the volume slider be tapped. It sits close to the seek bar and is easy to hit by mistake.",
            checked = prefs.settings.volumeControl,
            tag = "prefs.widget.volume",
            onChange = { prefs.onSettings(prefs.settings.copy(volumeControl = it)) },
        )

        Spacer(Modifier.height(28.dp))
        Limits()
    }
}

/** The widget's limits, on a tinted card with an icon. */
@Composable
private fun Limits() {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("prefs.widget.limits"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Good to know",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LIMITS.forEachIndexed { at, (title, body) ->
                if (at > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The widget's limits, each a title and an explanation. */
private val LIMITS =
    listOf(
        "The corners are trimmed" to
            "Android rounds off the corners of every widget, from every app, and there is no way to turn " +
            "that off. A smaller player would leave room to spare, but it would also make the buttons harder " +
            "to hit, so it keeps its size.",
        "It can be replaced by an icon" to
            "To save battery, Android sometimes stops apps it thinks you are done with. When that happens " +
            "the home screen shows an Andamp icon in place of the player - one tap brings it back. It should " +
            "not happen often.",
        "The bars are on a loop" to
            "About half a second of the music is turned into pictures, and the home screen plays those " +
            "round and round until a fresh half second replaces them. So the bars move with the music " +
            "without ever being quite in step with it. Keeping up beat for beat would mean sending new " +
            "pictures many times a second, which is what flattens a battery.",
        "The bars need the player running" to
            "Tapping the position or volume bar tells the player to move, so if Android has cleared Andamp " +
            "from memory there is nothing to tell it and the tap does nothing. The buttons still work: " +
            "pressing one starts the player again, and the bars follow.",
    )

/** One switch row. */
@Composable
private fun Toggle(
    title: String,
    summary: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/** One exclusive choice, as a row of segmented buttons. */
@Composable
private fun <T> Choice(
    title: String,
    summary: String,
    options: List<Pair<T, String>>,
    selected: T,
    tag: String,
    onPick: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { at, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onPick(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = at, count = options.size),
                    modifier = Modifier.testTag("$tag.${label.lowercase().substringBefore(' ')}"),
                ) {
                    // the names are chosen to fit; the ellipsis covers a bigger font scale or a
                    // longer translation
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
