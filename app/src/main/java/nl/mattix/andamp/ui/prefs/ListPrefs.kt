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
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.ListStyleStore
import nl.mattix.andamp.state.PlaylistFont
import nl.mattix.andamp.state.RowHeight
import nl.mattix.andamp.state.WinampViewModel

/** How the lists look: the playlist's font, and how tall a row is. */
data class ListPrefs(
    /** Mirrors [ListStyleStore.font]. */
    val font: PlaylistFont = ListStyleStore.DEFAULT_FONT,
    val onFont: (PlaylistFont) -> Unit = {},
    /** Mirrors [ListStyleStore.rowHeight]. */
    val rowHeight: RowHeight = ListStyleStore.DEFAULT_ROW_HEIGHT,
    val onRowHeight: (RowHeight) -> Unit = {},
)

/** The choices as [vm] has them: they show the render state, and a change goes to the store that state follows. */
internal fun listPrefsOf(vm: WinampViewModel) =
    ListPrefs(
        font = vm.state.playlistFont,
        onFont = { vm.listStyleStore.font = it },
        rowHeight = vm.state.rowHeight,
        onRowHeight = { vm.listStyleStore.rowHeight = it },
    )

@Composable
fun PlaylistFontRow(prefs: ListPrefs) {
    ChoiceRow(
        title = "Playlist font",
        about =
            when (prefs.font) {
                PlaylistFont.SKIN -> {
                    "The skin's own pixel letters, like the song title in the main window. A title in " +
                        "a script the skin has no letters for keeps the regular font."
                }

                PlaylistFont.REGULAR -> {
                    "A regular font, drawn smooth, with small and capital letters."
                }
            },
        choices = PlaylistFont.entries,
        chosen = prefs.font,
        onChoose = prefs.onFont,
        tag = "prefs.lists.font",
        label = { if (it == PlaylistFont.SKIN) "Skin" else "Regular" },
    )
}

@Composable
fun RowHeightRow(prefs: ListPrefs) {
    ChoiceRow(
        title = "Row height",
        about =
            when (prefs.rowHeight) {
                RowHeight.CLASSIC -> "Rows as tight as Winamp's. Pick a taller one if they are hard to tap."
                else -> "Taller rows in the playlist, the library and the skin list, with more room for a finger."
            },
        choices = RowHeight.entries,
        chosen = prefs.rowHeight,
        onChoose = prefs.onRowHeight,
        tag = "prefs.lists.rows",
        label = {
            when (it) {
                RowHeight.CLASSIC -> "Classic"
                RowHeight.ROOMY -> "Roomy"
                RowHeight.LARGE -> "Large"
            }
        },
    )
}

/** A setting with a few named values: its title, the values side by side, and what the chosen one does. */
@Suppress("LongParameterList") // a row's words, its values and where a pick goes
@Composable
private fun <T : Enum<T>> ChoiceRow(
    title: String,
    about: String,
    choices: List<T>,
    chosen: T,
    onChoose: (T) -> Unit,
    tag: String,
    label: (T) -> String,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            choices.forEachIndexed { at, choice ->
                SegmentedButton(
                    selected = choice == chosen,
                    onClick = { onChoose(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index = at, count = choices.size),
                    modifier = Modifier.testTag("$tag.${choice.name.lowercase()}"),
                ) { Text(label(choice)) }
            }
        }
        Text(about, style = MaterialTheme.typography.bodySmall)
    }
}
