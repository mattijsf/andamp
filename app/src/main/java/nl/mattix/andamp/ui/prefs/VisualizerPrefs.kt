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
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.PresetImport
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.presets.InstalledPack
import nl.mattix.andamp.ui.menu.countFor
import nl.mattix.andamp.ui.menu.idleLabel

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
    val packs: List<InstalledPack> = emptyList(),
    val onDeletePack: (InstalledPack) -> Unit = {},
    val onImportPack: () -> Unit = {},
    val importing: PresetImport? = null,
    /** The loaded pack's presets, in the engine's playlist order. */
    val presets: List<String> = emptyList(),
    /** The one showing, as the engine announced it. */
    val current: String? = null,
    /** Plays the preset at that index of [presets]. */
    val onSelectPreset: (Int) -> Unit = {},
    /** Opens the screen that browses [presets]; null where that screen already is. */
    val onManagePresets: (() -> Unit)? = null,
)

/** One line for the root list. */
fun VisualizerPrefs.summary(): String =
    when {
        packs.isEmpty() -> {
            "${plugin.menuLabel} · no preset packs yet"
        }

        else -> {
            // the packs list is already the current engine's; count its kind
            "${plugin.menuLabel} · ${packs.size} ${if (packs.size == 1) "pack" else "packs"}, " +
                "${packs.sumOf { it.countFor(plugin) }} presets"
        }
    }

@Composable
fun VisualizerSettings(prefs: VisualizerPrefs) {
    Section("Plug-in")
    Group(Modifier.selectableGroup()) {
        VisPlugin.entries.forEach { plugin ->
            RadioRow(
                title = plugin.menuLabel,
                summary = plugin.blurb(),
                selected = prefs.plugin == plugin,
                tag = "prefs.vis.plugin.${plugin.name.lowercase()}",
                onSelect = { prefs.onPlugin(plugin) },
            )
        }
    }
    PresetPacks(prefs)
}

private fun VisPlugin.blurb() =
    when (this) {
        VisPlugin.Avs -> "Winamp's own visualizer, rebuilt. Runs real .avs presets you import."
        VisPlugin.Milkdrop -> "libprojectM. Runs real .milk presets you import."
    }

@Composable
private fun PresetPacks(prefs: VisualizerPrefs) {
    Section("Preset packs")
    val importing = prefs.importing
    Note(
        when {
            importing?.failed == true -> {
                "\"${importing.name}\" held no presets."
            }

            importing != null -> {
                "Importing ${importing.name}... ${importing.filesWritten} files"
            }

            else -> {
                when (prefs.plugin) {
                    VisPlugin.Avs -> {
                        "Packs are .zip files of .avs presets. They are copied into the app, because AVS opens presets by path."
                    }

                    VisPlugin.Milkdrop -> {
                        "Packs are .zip files of .milk presets. " +
                            "They are copied into the app, because projectM opens presets by path."
                    }
                }
            }
        },
    )
    Button(
        onClick = prefs.onImportPack,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("prefs.vis.import"),
    ) { Text("Import pack...") }

    Spacer(Modifier.height(12.dp))
    Group(Modifier.selectableGroup()) {
        RadioRow(
            title = prefs.plugin.idleLabel,
            summary =
                when (prefs.plugin) {
                    VisPlugin.Avs -> "This app's own. No import needed."
                    VisPlugin.Milkdrop -> "The one built into libprojectM. No import needed."
                },
            selected = prefs.activePack == null,
            tag = "prefs.vis.pack.idle",
            onSelect = { prefs.onSelectPack(null) },
        )
        prefs.packs.forEach { pack ->
            RadioRow(
                title = pack.name,
                summary = "${pack.countFor(prefs.plugin)} presets",
                selected = prefs.activePack == pack.name,
                tag = "prefs.vis.pack.${pack.name}",
                onSelect = { prefs.onSelectPack(pack.name) },
                trailing = {
                    TextButton(
                        onClick = { prefs.onDeletePack(pack) },
                        modifier = Modifier.testTag("prefs.vis.pack.${pack.name}.delete"),
                    ) { Text("Remove") }
                },
            )
        }
    }

    prefs.onManagePresets?.let { manage ->
        TextButton(onClick = manage, modifier = Modifier.padding(horizontal = 8.dp).testTag("prefs.vis.manage")) {
            Text(if (prefs.presets.isEmpty()) "Browse presets" else "Browse ${prefs.presets.size} presets")
        }
    }

    Spacer(Modifier.height(12.dp))
    Group {
        SwitchRow(
            title = "Shuffle presets",
            summary = "Otherwise they run in the order they are on disk.",
            checked = prefs.shuffle,
            tag = "prefs.vis.shuffle",
            onChange = prefs.onShuffle,
        )
    }
}
