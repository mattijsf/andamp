// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.state.PresetNotice
import nl.mattix.andamp.state.presets.PresetDetails
import nl.mattix.andamp.ui.rememberWords
import nl.mattix.andamp.visualizer.core.PresetGaps

/**
 * One preset, in full: what the file is, what it is made of, what it asks for, and what the
 * engine could not show of it. Opened from the preset selector's list.
 *
 * @param details null while the file is being read.
 */
@Composable
internal fun PresetDetailsPage(
    details: PresetDetails?,
    gaps: PresetGaps?,
    favorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onForget: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag("preset.details"),
    ) {
        if (details == null) {
            Note(stringResource(R.string.preset_details_reading))
            return@Column
        }
        Text(details.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        details.author?.let {
            Text(
                stringResource(R.string.preset_details_by, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        gaps?.let { PresetNotice.detailFor(it, rememberWords()) }?.let { problem ->
            Text(
                problem,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (gaps.unreadable ||
                        gaps.blank
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.padding(top = 8.dp).testTag("preset.details.problem"),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = onPlay, modifier = Modifier.testTag("preset.details.play")) {
                Text(stringResource(R.string.preset_details_play))
            }
            OutlinedButton(onClick = onToggleFavorite, modifier = Modifier.testTag("preset.details.favorite")) {
                Text(stringResource(if (favorite) R.string.menu_remove_favorite else R.string.menu_add_favorite))
            }
            // only a preset that is skipped has something to stop
            if (gaps != null && (gaps.unreadable || gaps.blank)) {
                TextButton(onClick = onForget, modifier = Modifier.testTag("preset.details.forget")) {
                    Text(stringResource(R.string.preset_details_forget))
                }
            }
        }

        FileFacts(details)
        details.milk?.let { MilkFacts(it) }
        details.avs?.let { avs ->
            Section(stringResource(R.string.preset_details_avs_section))
            Group {
                Fact(
                    stringResource(R.string.preset_details_clears),
                    stringResource(if (avs.clearEveryFrame) R.string.preset_details_yes else R.string.preset_details_no),
                )
            }
            Section(stringResource(R.string.preset_details_components))
            Group {
                avs.parts.forEach { part ->
                    val shown = gaps?.missing?.contains(part.name) != true
                    Fact(
                        " ".repeat(part.depth) + part.name,
                        if (shown) null else stringResource(R.string.preset_details_not_shown),
                        tag = "preset.details.part.${part.name}",
                    )
                }
            }
        }
        if (details.milk == null && details.avs == null) Note(stringResource(R.string.preset_details_unparsed))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FileFacts(details: PresetDetails) {
    val context = LocalContext.current
    Section(stringResource(R.string.preset_details_file_section))
    Group {
        details.pack?.let { Fact(stringResource(R.string.preset_details_pack), it) }
        details.folder?.let { Fact(stringResource(R.string.preset_details_folder), it) }
        details.fingerprint?.let {
            Fact(stringResource(R.string.preset_details_size), Formatter.formatShortFileSize(context, it.size))
            Fact(stringResource(R.string.preset_details_sha256), it.sha256, mono = true)
        }
    }
}

@Composable
private fun MilkFacts(milk: PresetDetails.Milk) {
    val none = stringResource(R.string.preset_details_none)

    @Composable
    fun lines(count: Int) = if (count == 0) none else pluralStringResource(R.plurals.preset_details_lines, count, count)

    Section(stringResource(R.string.preset_details_milkdrop_section))
    Group {
        milk.version?.let { Fact(stringResource(R.string.preset_details_version), it.toString()) }
        if (milk.warpModel != null || milk.compModel != null) {
            Fact(
                stringResource(R.string.preset_details_shader_model),
                stringResource(
                    R.string.preset_details_shader_model_value,
                    milk.warpModel?.toString() ?: none,
                    milk.compModel?.toString() ?: none,
                ),
            )
        }
        Fact(stringResource(R.string.preset_details_warp), lines(milk.warpLines), tag = "preset.details.warp")
        Fact(stringResource(R.string.preset_details_comp), lines(milk.compLines))
        Fact(stringResource(R.string.preset_details_per_frame), lines(milk.perFrameLines))
        Fact(stringResource(R.string.preset_details_per_pixel), lines(milk.perPixelLines))
        Fact(stringResource(R.string.preset_details_waves), milk.customWaves.toString())
        Fact(stringResource(R.string.preset_details_shapes), milk.customShapes.toString())
        Fact(
            stringResource(R.string.preset_details_q),
            milk.qVariables.takeIf { it.isNotEmpty() }?.joinToString(", ") { "q$it" } ?: none,
        )
    }
    if (milk.textures.isNotEmpty()) {
        Section(stringResource(R.string.preset_details_textures_section))
        Group {
            milk.textures.forEach { texture ->
                Fact(
                    texture.name,
                    stringResource(
                        when (texture.found) {
                            true -> R.string.preset_details_texture_found
                            false -> R.string.preset_details_texture_missing
                            null -> R.string.preset_details_texture_random
                        },
                    ),
                    tag = "preset.details.texture.${texture.name}",
                )
            }
        }
    }
    if (milk.settings.isNotEmpty()) {
        Section(stringResource(R.string.preset_details_settings_section))
        Group { milk.settings.forEach { (key, value) -> Fact(key, value, mono = true) } }
    }
}

/** A name and what it is; the value wraps, and can be copied. */
@Composable
private fun Fact(
    label: String,
    value: String?,
    mono: Boolean = false,
    tag: String? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent =
            value?.let {
                {
                    SelectionContainer {
                        Text(
                            it,
                            fontFamily = if (mono) FontFamily.Monospace else null,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
        colors = rowColors(),
        modifier = Modifier.prefsRow().let { if (tag != null) it.testTag(tag) else it },
    )
}
