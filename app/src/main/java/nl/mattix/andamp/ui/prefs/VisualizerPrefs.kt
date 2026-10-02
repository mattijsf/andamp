// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    Column(Modifier.selectableGroup()) {
        VisPlugin.entries.forEach { plugin ->
            Row(
                Modifier
                    .fillMaxWidth()
                    // the whole row is the target
                    .selectable(
                        selected = prefs.plugin == plugin,
                        role = Role.RadioButton,
                        onClick = { prefs.onPlugin(plugin) },
                    ).testTag("prefs.vis.plugin.${plugin.name.lowercase()}")
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = prefs.plugin == plugin, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(plugin.menuLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(plugin.blurb(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
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
    Text(
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
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = prefs.onImportPack, modifier = Modifier.testTag("prefs.vis.import")) { Text("Import pack...") }

    Spacer(Modifier.height(8.dp))
    PackRow(
        label = prefs.plugin.idleLabel,
        detail =
            when (prefs.plugin) {
                VisPlugin.Avs -> "This app's own. No import needed."
                VisPlugin.Milkdrop -> "The one built into libprojectM. No import needed."
            },
        selected = prefs.activePack == null,
        onSelect = { prefs.onSelectPack(null) },
        tag = "prefs.vis.pack.idle",
    )
    prefs.packs.forEach { pack ->
        PackRow(
            label = pack.name,
            detail = "${pack.countFor(prefs.plugin)} presets",
            selected = prefs.activePack == pack.name,
            onSelect = { prefs.onSelectPack(pack.name) },
            tag = "prefs.vis.pack.${pack.name}",
            onDelete = { prefs.onDeletePack(pack) },
        )
    }

    prefs.onManagePresets?.let { manage ->
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = manage, modifier = Modifier.testTag("prefs.vis.manage")) {
            Text(if (prefs.presets.isEmpty()) "Browse presets" else "Browse ${prefs.presets.size} presets")
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Shuffle presets", style = MaterialTheme.typography.bodyLarge)
            Text("Otherwise they run in the order they are on disk.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = prefs.shuffle,
            onCheckedChange = prefs.onShuffle,
            modifier = Modifier.testTag("prefs.vis.shuffle"),
        )
    }
}

@Composable
private fun PackRow(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
    tag: String,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete, modifier = Modifier.testTag("$tag.delete")) { Text("Remove") }
        }
    }
}
