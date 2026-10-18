// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.state.ListFont
import nl.mattix.andamp.state.ListStyleStore
import nl.mattix.andamp.state.RowHeight
import nl.mattix.andamp.state.WinampViewModel

/** How the lists look: the font of the playlist and the library, and how tall a row is. */
data class ListPrefs(
    /** Mirrors [ListStyleStore.font]. */
    val font: ListFont = ListStyleStore.DEFAULT_FONT,
    val onFont: (ListFont) -> Unit = {},
    /** Mirrors [ListStyleStore.rowHeight]. */
    val rowHeight: RowHeight = ListStyleStore.DEFAULT_ROW_HEIGHT,
    val onRowHeight: (RowHeight) -> Unit = {},
)

/** The choices as [vm] has them: they show the render state, and a change goes to the store that state follows. */
internal fun listPrefsOf(vm: WinampViewModel) =
    ListPrefs(
        font = vm.state.listFont,
        onFont = { vm.listStyleStore.font = it },
        rowHeight = vm.state.rowHeight,
        onRowHeight = { vm.listStyleStore.rowHeight = it },
    )

@Composable
fun ListFontRow(prefs: ListPrefs) = ListFontChoice(prefs.font, prefs.onFont, tag = "prefs.lists.font")

/** A choice of the font the lists are written in, [font] now; the phone's, or the one in Android Auto. */
@Composable
internal fun ListFontChoice(
    font: ListFont,
    onFont: (ListFont) -> Unit,
    tag: String,
) {
    ChoiceRow(
        title = stringResource(R.string.prefs_lists_font_title),
        about =
            stringResource(
                when (font) {
                    ListFont.SKIN -> R.string.prefs_lists_font_skin_about
                    ListFont.REGULAR -> R.string.prefs_lists_font_regular_about
                },
            ),
        choices = ListFont.entries,
        chosen = font,
        onChoose = onFont,
        tag = tag,
        label = { if (it == ListFont.SKIN) R.string.prefs_lists_font_skin else R.string.prefs_lists_font_regular },
    )
}

@Composable
fun RowHeightRow(prefs: ListPrefs) {
    ChoiceRow(
        title = stringResource(R.string.prefs_lists_row_height_title),
        about =
            stringResource(
                when (prefs.rowHeight) {
                    RowHeight.CLASSIC -> R.string.prefs_lists_row_height_classic_about
                    else -> R.string.prefs_lists_row_height_taller_about
                },
            ),
        choices = RowHeight.entries,
        chosen = prefs.rowHeight,
        onChoose = prefs.onRowHeight,
        tag = "prefs.lists.rows",
        label = {
            when (it) {
                RowHeight.CLASSIC -> R.string.prefs_lists_row_classic
                RowHeight.ROOMY -> R.string.prefs_lists_row_roomy
                RowHeight.LARGE -> R.string.prefs_lists_row_large
            }
        },
    )
}

/**
 * A setting with a few named values: its title, the values side by side, and what the chosen one does.
 * [label] gives the string resource that names a value.
 */
@Suppress("LongParameterList") // a row's words, its values and where a pick goes
@Composable
private fun <T : Enum<T>> ChoiceRow(
    title: String,
    about: String,
    choices: List<T>,
    chosen: T,
    onChoose: (T) -> Unit,
    tag: String,
    label: (T) -> Int,
) {
    Column(Modifier.prefsRow().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            choices.forEachIndexed { at, choice ->
                SegmentedButton(
                    selected = choice == chosen,
                    onClick = { onChoose(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index = at, count = choices.size),
                    modifier = Modifier.testTag("$tag.${choice.name.lowercase()}"),
                ) { Text(stringResource(label(choice))) }
            }
        }
        Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
