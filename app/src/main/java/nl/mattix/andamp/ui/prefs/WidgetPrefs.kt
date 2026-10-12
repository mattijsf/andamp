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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.Words
import nl.mattix.andamp.ui.menu.shortLabelRes
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
fun WidgetPrefs.summary(words: Words): String {
    val refresh = words.get(settings.refresh.inSummary)
    return when (settings.mode) {
        VisMode.Off -> {
            words.get(R.string.prefs_widget_summary_no_vis, refresh)
        }

        VisMode.Analyzer -> {
            words.get(R.string.prefs_widget_summary, words.get(R.string.prefs_widget_summary_analyzer), refresh)
        }

        VisMode.Oscilloscope -> {
            words.get(
                R.string.prefs_widget_summary,
                words.get(R.string.prefs_widget_summary_oscilloscope),
                refresh,
            )
        }
    }
}

/**
 * A refresh rate as the summary line words it. A resource apart from the choice's label, because
 * how a word is written inside a sentence differs by language: lower case in English, a capital
 * for a noun in German.
 */
private val WidgetRefresh.inSummary: Int
    get() =
        when (this) {
            WidgetRefresh.LOW -> R.string.prefs_widget_summary_refresh_low
            WidgetRefresh.MEDIUM -> R.string.prefs_widget_summary_refresh_medium
            WidgetRefresh.HIGH -> R.string.prefs_widget_summary_refresh_high
        }

/**
 * What each visualizer mode is, in a sentence, as a string resource. The names come from
 * [shortLabelRes], which the player's Visualization menu uses too.
 */
private val VisMode.summary: Int
    get() =
        when (this) {
            VisMode.Analyzer -> R.string.prefs_widget_vis_analyzer
            VisMode.Oscilloscope -> R.string.prefs_widget_vis_oscilloscope
            VisMode.Off -> R.string.prefs_widget_vis_off
        }

/**
 * The widget page's rows, in a Column of their own: a caller that does not stack its children would
 * draw them on top of each other, and a covered row receives no touches.
 */
@Composable
fun WidgetRows(prefs: WidgetPrefs) {
    Column {
        Spacer(Modifier.height(8.dp))
        Group {
            // Off is one of the modes, as in VisMode and the player's own visualizer menu, so there is
            // no separate switch
            Choice(
                title = stringResource(R.string.prefs_widget_visualizer_title),
                summary = stringResource(prefs.settings.mode.summary),
                // in VisMode's own order
                options = VisMode.entries.map { it to it.shortLabelRes },
                selected = prefs.settings.mode,
                tag = "prefs.widget.vis",
                onPick = { prefs.onSettings(prefs.settings.copy(mode = it)) },
            )

            // the refresh rate drives the clock as well as the visualizer, so it stays offered with the
            // visualizer off, with a summary about the time only
            Choice(
                title = stringResource(R.string.prefs_widget_refresh_title),
                summary =
                    stringResource(
                        if (prefs.settings.mode == VisMode.Off) {
                            prefs.settings.refresh.timeOnly
                        } else {
                            prefs.settings.refresh.summary
                        },
                    ),
                options = WidgetRefresh.entries.map { it to it.label },
                selected = prefs.settings.refresh,
                tag = "prefs.widget.refresh",
                onPick = { prefs.onSettings(prefs.settings.copy(refresh = it)) },
            )

            // a choice, because a cell is almost never a whole number of players wide: either the art
            // stops short of the edge or it leaves the whole-pixel grid. The trimmed corners are not
            // part of this choice; Android trims them either way
            Choice(
                title = stringResource(R.string.prefs_widget_size_title),
                summary = stringResource(prefs.settings.size.summary),
                options = WidgetSize.entries.map { it to it.label },
                selected = prefs.settings.size,
                tag = "prefs.widget.size",
                onPick = { prefs.onSettings(prefs.settings.copy(size = it)) },
            )

            Toggle(
                title = stringResource(R.string.prefs_widget_logo_title),
                summary = stringResource(R.string.prefs_widget_logo_summary),
                checked = prefs.settings.openOnLogo,
                tag = "prefs.widget.logo",
                onChange = { prefs.onSettings(prefs.settings.copy(openOnLogo = it)) },
            )

            Toggle(
                title = stringResource(R.string.prefs_widget_volume_title),
                summary = stringResource(R.string.prefs_widget_volume_summary),
                checked = prefs.settings.volumeControl,
                tag = "prefs.widget.volume",
                onChange = { prefs.onSettings(prefs.settings.copy(volumeControl = it)) },
            )
        }

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
                    stringResource(R.string.prefs_widget_limits_title),
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
                    stringResource(title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The widget's limits, each a title and an explanation, as string resources. */
private val LIMITS =
    listOf(
        R.string.prefs_widget_limit_corners_title to R.string.prefs_widget_limit_corners_body,
        R.string.prefs_widget_limit_icon_title to R.string.prefs_widget_limit_icon_body,
        R.string.prefs_widget_limit_loop_title to R.string.prefs_widget_limit_loop_body,
        R.string.prefs_widget_limit_running_title to R.string.prefs_widget_limit_running_body,
    )

/** One switch row. */
@Composable
private fun Toggle(
    title: String,
    summary: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) = SwitchRow(title = title, summary = summary, checked = checked, tag = tag, onChange = onChange)

/**
 * One exclusive choice, as a row of segmented buttons. Each of [options] is a value and the string
 * resource that names it; a button is tagged [tag] and its value's name in lower case.
 */
@Composable
private fun <T : Enum<T>> Choice(
    title: String,
    summary: String,
    options: List<Pair<T, Int>>,
    selected: T,
    tag: String,
    onPick: (T) -> Unit,
) {
    Column(Modifier.prefsRow().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { at, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onPick(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = at, count = options.size),
                    modifier = Modifier.testTag("$tag.${value.name.lowercase()}"),
                ) {
                    // the names are chosen to fit; the ellipsis covers a bigger font scale or a
                    // longer translation
                    Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
