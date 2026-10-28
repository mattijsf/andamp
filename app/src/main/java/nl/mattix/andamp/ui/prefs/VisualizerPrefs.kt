// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.state.PresetImport
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.presets.InstalledPack
import nl.mattix.andamp.ui.menu.countFor
import nl.mattix.andamp.ui.menu.idleLabelRes

/**
 * The plug-in window's settings, handed to the page as data so the page does not see a ViewModel:
 * the engine, which packs are installed, importing one and removing one. The engine choice is also
 * in Winamp's "Select plug-in" menu.
 */
data class VisualizerPrefs(
    val plugin: VisPlugin = VisPlugin.Avs,
    val onPlugin: (VisPlugin) -> Unit = {},
    val shuffle: Boolean = false,
    val onShuffle: (Boolean) -> Unit = {},
    val activePack: String? = null,
    val onSelectPack: (String?) -> Unit = {},
    /** Whether the engine plays the favorites rather than [activePack]. */
    val favoritesOn: Boolean = false,
    /** How many favorites the engine has, from every pack. */
    val favoriteCount: Int = 0,
    val onSelectFavorites: () -> Unit = {},
    val packs: List<InstalledPack> = emptyList(),
    val onDeletePack: (InstalledPack) -> Unit = {},
    val onImportPack: () -> Unit = {},
    val importing: PresetImport? = null,
    /** The loaded pack's presets, in the engine's playlist order. */
    val presets: List<String> = emptyList(),
    /** Opens the screen that browses [presets]; null where that screen already is. */
    val onManagePresets: (() -> Unit)? = null,
)

@Composable
fun VisualizerSettings(prefs: VisualizerPrefs) {
    Section(stringResource(R.string.prefs_visualizer_plugin_section))
    Group(Modifier.selectableGroup()) {
        VisPlugin.entries.forEach { plugin ->
            RadioRow(
                title = plugin.menuLabel,
                summary = stringResource(plugin.blurb()),
                selected = prefs.plugin == plugin,
                tag = "prefs.vis.plugin.${plugin.name.lowercase()}",
                onSelect = { prefs.onPlugin(plugin) },
            )
        }
    }
    PresetPacks(prefs)
}

/** The string resource that says what an engine is. */
private fun VisPlugin.blurb(): Int =
    when (this) {
        VisPlugin.Avs -> R.string.prefs_visualizer_avs_blurb
        VisPlugin.Milkdrop -> R.string.prefs_visualizer_milkdrop_blurb
    }

@Composable
private fun PresetPacks(prefs: VisualizerPrefs) {
    Section(stringResource(R.string.prefs_visualizer_packs_section))
    val importing = prefs.importing
    Note(
        when {
            importing?.failed == true -> {
                stringResource(R.string.prefs_visualizer_import_empty, importing.name)
            }

            importing != null -> {
                stringResource(R.string.prefs_visualizer_importing, importing.name, importing.filesWritten)
            }

            else -> {
                stringResource(
                    when (prefs.plugin) {
                        VisPlugin.Avs -> R.string.prefs_visualizer_packs_note_avs
                        VisPlugin.Milkdrop -> R.string.prefs_visualizer_packs_note_milkdrop
                    },
                )
            }
        },
    )
    Button(
        onClick = prefs.onImportPack,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("prefs.vis.import"),
    ) { Text(stringResource(R.string.prefs_visualizer_import)) }

    Spacer(Modifier.height(12.dp))
    Group(Modifier.selectableGroup()) {
        RadioRow(
            title = stringResource(prefs.plugin.idleLabelRes),
            summary =
                stringResource(
                    when (prefs.plugin) {
                        VisPlugin.Avs -> R.string.prefs_visualizer_idle_avs
                        VisPlugin.Milkdrop -> R.string.prefs_visualizer_idle_milkdrop
                    },
                ),
            selected = !prefs.favoritesOn && prefs.activePack == null,
            tag = "prefs.vis.pack.idle",
            onSelect = { prefs.onSelectPack(null) },
        )
        RadioRow(
            title = stringResource(R.string.prefs_visualizer_favorites),
            summary =
                if (prefs.favoriteCount == 0) {
                    stringResource(R.string.prefs_visualizer_favorites_none)
                } else {
                    pluralStringResource(R.plurals.prefs_visualizer_favorites_summary, prefs.favoriteCount, prefs.favoriteCount)
                },
            selected = prefs.favoritesOn,
            enabled = prefs.favoriteCount > 0,
            tag = "prefs.vis.pack.favorites",
            onSelect = prefs.onSelectFavorites,
        )
        prefs.packs.forEach { pack ->
            RadioRow(
                title = pack.name,
                summary = stringResource(R.string.prefs_visualizer_pack_presets, pack.countFor(prefs.plugin)),
                selected = !prefs.favoritesOn && prefs.activePack == pack.name,
                tag = "prefs.vis.pack.${pack.name}",
                onSelect = { prefs.onSelectPack(pack.name) },
                trailing = {
                    TextButton(
                        onClick = { prefs.onDeletePack(pack) },
                        modifier = Modifier.testTag("prefs.vis.pack.${pack.name}.delete"),
                    ) { Text(stringResource(R.string.prefs_visualizer_remove)) }
                },
            )
        }
    }

    prefs.onManagePresets?.let { manage ->
        TextButton(onClick = manage, modifier = Modifier.padding(horizontal = 8.dp).testTag("prefs.vis.manage")) {
            Text(
                if (prefs.presets.isEmpty()) {
                    stringResource(R.string.prefs_visualizer_browse)
                } else {
                    stringResource(R.string.prefs_visualizer_browse_count, prefs.presets.size)
                },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Group {
        SwitchRow(
            title = stringResource(R.string.prefs_visualizer_shuffle_title),
            summary = stringResource(R.string.prefs_visualizer_shuffle_summary),
            checked = prefs.shuffle,
            tag = "prefs.vis.shuffle",
            onChange = prefs.onShuffle,
        )
    }
}
