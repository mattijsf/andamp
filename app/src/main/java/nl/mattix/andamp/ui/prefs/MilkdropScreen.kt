// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import nl.mattix.andamp.R
import nl.mattix.andamp.state.PresetNotice
import nl.mattix.andamp.ui.menu.idleLabelRes
import nl.mattix.andamp.ui.rememberWords
import nl.mattix.andamp.visualizer.core.PresetGaps
import java.io.File

/**
 * The plug-in's preset selector, opened from the visual's long press: the visual playing at the
 * top, with what can be done to the preset showing under it, then every preset of the source
 * playing, and on a second tab the plug-in's settings.
 *
 * A source is the engine's idle preset, one pack, or the favorites, which come from every pack
 * and play like one. The list is that source's, so a tap, Next and Shuffle all stay inside it.
 * Each preset says what the engine could not show of it, as far as that is known.
 *
 * The settings are the same composable the Visualizer preferences page shows
 * ([VisualizerSettings]). The list is lazy, because a pack can hold thousands of presets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MilkdropScreen(
    prefs: VisualizerPrefs,
    selector: PresetSelector,
    onClose: () -> Unit,
    /** The visual itself; the screen gives it its place. Left out in tests, which have no engine. */
    preview: @Composable (Modifier) -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_PRESETS) }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(prefs.plugin.menuLabel) },
                    actions = {
                        TextButton(onClick = onClose, modifier = Modifier.testTag("milkdrop.close")) {
                            Text(stringResource(R.string.prefs_milkdrop_done))
                        }
                    },
                )
            },
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                // the preview leaves most of a landscape phone to the list
                val previewH = min(PREVIEW_H.dp, maxHeight * PREVIEW_SHARE)
                Column(Modifier.fillMaxSize()) {
                    preview(
                        Modifier
                            .fillMaxWidth()
                            .height(previewH)
                            .background(Color.Black)
                            .testTag("milkdrop.preview"),
                    )
                    NowShowing(prefs, selector)
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == TAB_PRESETS,
                            onClick = { tab = TAB_PRESETS },
                            text = { Text(stringResource(R.string.prefs_milkdrop_presets_section)) },
                            modifier = Modifier.testTag("milkdrop.tab.presets"),
                        )
                        Tab(
                            selected = tab == TAB_SETTINGS,
                            onClick = { tab = TAB_SETTINGS },
                            text = { Text(stringResource(R.string.prefs_milkdrop_settings_tab)) },
                            modifier = Modifier.testTag("milkdrop.tab.settings"),
                        )
                    }
                    when (tab) {
                        TAB_PRESETS -> PresetList(prefs, selector)
                        else -> Settings(prefs)
                    }
                }
            }
        }
    }
}

/** The preset playing, its problems, and the buttons that act on it. */
@Composable
private fun NowShowing(
    prefs: VisualizerPrefs,
    selector: PresetSelector,
) {
    val words = rememberWords()
    val current = selector.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                selector.currentName ?: stringResource(prefs.plugin.idleLabelRes),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("milkdrop.current"),
            )
            PresetNotice.detailFor(selector.currentGaps, words)?.let { problem ->
                Problem(problem, selector.currentGaps, Modifier.testTag("milkdrop.current.problem"))
            }
        }
        IconButton(onClick = selector.onPrevious, modifier = Modifier.testTag("milkdrop.previous")) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.menu_previous_preset))
        }
        IconButton(onClick = selector.onNext, modifier = Modifier.testTag("milkdrop.next")) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.menu_next_preset))
        }
        // filled while on, so its state reads at a glance
        FilledTonalIconToggleButton(
            checked = prefs.shuffle,
            onCheckedChange = prefs.onShuffle,
            modifier = Modifier.testTag("milkdrop.shuffle"),
        ) {
            Icon(Icons.Filled.Shuffle, contentDescription = stringResource(R.string.menu_shuffle))
        }
        Star(
            favorite = current != null && selector.isFavorite(current),
            enabled = current != null,
            onToggle = { current?.let(selector.onToggleFavorite) },
            tag = "milkdrop.current.favorite",
        )
    }
}

@Composable
private fun PresetList(
    prefs: VisualizerPrefs,
    selector: PresetSelector,
) {
    val list = rememberLazyListState()
    val paths = selector.paths
    // a new list is checked ahead, so its rows can say what they will miss before one is picked
    LaunchedEffect(paths) { selector.onShown(paths) }
    // the list follows the preset playing when Next or Shuffle takes it out of view
    LaunchedEffect(selector.current, paths) {
        val index = paths.indexOf(selector.current)
        if (index < 0) return@LaunchedEffect
        val item = index + HEADER_ITEMS
        val shown = list.layoutInfo.visibleItemsInfo
        if (shown.none { it.index == item }) list.scrollToItem(item)
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("milkdrop.presets")) {
        item { Sources(prefs) }
        item { ListHeader(prefs, selector) }
        items(paths, key = { it }) { path ->
            PresetRow(
                path = path,
                playing = path == selector.current,
                favorite = selector.isFavorite(path),
                gaps = selector.gapsOf(path),
                onPlay = { selector.onPlay(path) },
                onToggleFavorite = { selector.onToggleFavorite(path) },
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** What can play: the favorites, the engine's idle preset, and each pack. */
@Composable
private fun Sources(prefs: VisualizerPrefs) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FilterChip(
            selected = prefs.favoritesOn,
            onClick = prefs.onSelectFavorites,
            enabled = prefs.favoriteCount > 0,
            label = { Text(stringResource(R.string.prefs_milkdrop_favorites_chip, prefs.favoriteCount)) },
            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
            modifier = Modifier.testTag("milkdrop.source.favorites"),
        )
        FilterChip(
            selected = !prefs.favoritesOn && prefs.activePack == null,
            onClick = { prefs.onSelectPack(null) },
            label = { Text(stringResource(prefs.plugin.idleLabelRes)) },
            modifier = Modifier.testTag("milkdrop.source.idle"),
        )
        prefs.packs.forEach { pack ->
            FilterChip(
                selected = !prefs.favoritesOn && prefs.activePack == pack.name,
                onClick = { prefs.onSelectPack(pack.name) },
                label = { Text(pack.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("milkdrop.source.${pack.name}"),
            )
        }
    }
}

@Composable
private fun ListHeader(
    prefs: VisualizerPrefs,
    selector: PresetSelector,
) {
    val pack = prefs.activePack
    Text(
        when {
            prefs.favoritesOn -> {
                pluralStringResource(R.plurals.prefs_milkdrop_favorites_count, selector.paths.size, selector.paths.size)
            }

            pack == null -> {
                stringResource(
                    if (prefs.favoriteCount >
                        0
                    ) {
                        R.string.prefs_milkdrop_idle_playing
                    } else {
                        R.string.prefs_milkdrop_idle_no_favorites
                    },
                    stringResource(prefs.plugin.idleLabelRes),
                )
            }

            selector.paths.isEmpty() -> {
                stringResource(R.string.prefs_milkdrop_pack_loading)
            }

            else -> {
                pluralStringResource(R.plurals.prefs_milkdrop_pack_count, selector.paths.size, selector.paths.size, pack)
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("milkdrop.list.header"),
    )
}

@Composable
private fun PresetRow(
    path: String,
    playing: Boolean,
    favorite: Boolean,
    gaps: PresetGaps?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val name = File(path).nameWithoutExtension
    val words = rememberWords()
    val background = if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = playing, role = Role.Button, onClick = onPlay)
                .background(background)
                .testTag("milkdrop.preset.$name")
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            gaps?.let { PresetNotice.detailFor(it, words) }?.let { problem ->
                Problem(problem, gaps, Modifier.testTag("milkdrop.preset.$name.problem"))
            }
        }
        Star(favorite = favorite, enabled = true, onToggle = onToggleFavorite, tag = "milkdrop.preset.$name.favorite")
    }
}

/** What the engine could not show: in the error color when nothing of the preset shows. */
@Composable
private fun Problem(
    text: String,
    gaps: PresetGaps,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color =
            if (gaps.unreadable ||
                gaps.blank
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun Star(
    favorite: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    tag: String,
) {
    IconToggleButton(checked = favorite, onCheckedChange = { onToggle() }, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(
            if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            contentDescription =
                stringResource(if (favorite) R.string.menu_remove_favorite else R.string.menu_add_favorite),
        )
    }
}

@Composable
private fun Settings(prefs: VisualizerPrefs) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag("milkdrop.settings"),
    ) {
        VisualizerSettings(prefs)
        Spacer(Modifier.height(32.dp))
    }
}

private const val TAB_PRESETS = 0
private const val TAB_SETTINGS = 1

/** The sources and the line about the list sit above the presets. */
private const val HEADER_ITEMS = 2

/** The preview's height, at most this share of the room below the bar. */
private const val PREVIEW_H = 200
private const val PREVIEW_SHARE = 0.3f
