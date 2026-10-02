// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.ui.menu.idleLabel

/**
 * The plug-in's own screen: which pack is loaded, and every preset in it.
 *
 * The settings above the list are the same composable the Visualizer preferences page shows
 * ([VisualizerSettings]). This screen opens from the visual's long press; that page from
 * Preferences.
 *
 * The list is lazy, because a pack can hold thousands of presets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MilkdropScreen(
    prefs: VisualizerPrefs,
    onClose: () -> Unit,
) {
    val list = rememberLazyListState()
    val playing = prefs.presets.indexOf(prefs.current)
    // A long list opens on the preset that is running. A short one opens at the top, where the pack
    // settings are.
    LaunchedEffect(playing) {
        if (playing >= 0 && prefs.presets.size > SCROLL_TO_PLAYING_ABOVE) {
            list.scrollToItem(playing + HEADER_ITEMS)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(prefs.plugin.menuLabel) },
                    actions = {
                        TextButton(onClick = onClose, modifier = Modifier.testTag("milkdrop.close")) { Text("Done") }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                state = list,
                modifier =
                    Modifier
                        .padding(padding)
                        .testTag("milkdrop.presets"),
            ) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        VisualizerSettings(prefs)
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider()
                    }
                }
                item { PresetsHeader(prefs) }
                itemsIndexed(prefs.presets) { index, name ->
                    PresetRow(
                        name = name,
                        playing = name == prefs.current,
                        onPlay = { prefs.onSelectPreset(index) },
                    )
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun PresetsHeader(prefs: VisualizerPrefs) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Section("Presets")
        Text(
            when {
                prefs.activePack == null -> {
                    "Nothing is loaded but the ${prefs.plugin.idleLabel}. Import a pack above."
                }

                prefs.presets.isEmpty() -> {
                    "The pack is loading, or the plug-in window is closed."
                }

                else -> {
                    "${prefs.presets.size} in ${prefs.activePack}, in the order the engine plays them."
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PresetRow(
    name: String,
    playing: Boolean,
    onPlay: () -> Unit,
) {
    val background = if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Text(
        name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (playing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = playing, role = Role.Button, onClick = onPlay)
                .background(background)
                .testTag("milkdrop.preset.$name")
                .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/** The settings block and the "Presets" header sit above the list itself. */
private const val HEADER_ITEMS = 2

/** Lists longer than this open scrolled to the running preset. */
private const val SCROLL_TO_PLAYING_ABOVE = 30
