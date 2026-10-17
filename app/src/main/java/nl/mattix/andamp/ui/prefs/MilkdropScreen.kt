// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.R
import nl.mattix.andamp.state.PresetNotice
import nl.mattix.andamp.state.presets.PresetDetails
import nl.mattix.andamp.state.presets.PresetSearch
import nl.mattix.andamp.state.presets.PresetTitle
import nl.mattix.andamp.ui.menu.idleLabelRes
import nl.mattix.andamp.ui.pulledAside
import nl.mattix.andamp.ui.rememberBackPull
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
    // the preset whose page is open over the list, by file
    var about by rememberSaveable { mutableStateOf<String?>(null) }
    // back from a preset's page pulls it aside onto the list under it
    val aboutPull = rememberBackPull(enabled = about != null) { about = null }
    // a completed gesture leaves the pull at its end; once the page is gone it goes back to rest,
    // or the next page would open pulled aside
    LaunchedEffect(about) { if (about == null) aboutPull.rest() }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (about != null) stringResource(R.string.preset_details_open) else prefs.plugin.menuLabel) },
                    // back to the list from a preset's page, and to the player from here
                    navigationIcon = {
                        val toList = about != null
                        IconButton(
                            onClick = { if (toList) about = null else onClose() },
                            modifier = Modifier.testTag(if (toList) "preset.details.back" else "milkdrop.close"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription =
                                    stringResource(if (toList) R.string.preset_details_back else R.string.prefs_back_to_player),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                // the preview leaves most of a landscape phone to the list
                val previewH = min(PREVIEW_H.dp, maxHeight * PREVIEW_SHARE)
                Column(Modifier.fillMaxSize().then(if (about != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                    preview(
                        Modifier
                            .fillMaxWidth()
                            .height(previewH)
                            .background(Color.Black)
                            .testTag("milkdrop.preview"),
                    )
                    NowShowing(prefs, selector, onAbout = { about = it })
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
                        TAB_PRESETS -> PresetList(prefs, selector, onAbout = { about = it })
                        else -> Settings(prefs)
                    }
                }
                // a preset's page covers the list whole: the visual and the preset playing beside
                // it would read as the preset the page is about
                about?.let { path ->
                    Surface(Modifier.fillMaxSize().pulledAside(aboutPull)) {
                        AboutPreset(path, selector, onPlayed = { about = null })
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
    onAbout: (String) -> Unit,
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
                // the name opens the preset's page; the idle preset has none
                modifier =
                    Modifier
                        .let { m ->
                            current?.let {
                                m.clickable(
                                    onClickLabel = stringResource(R.string.preset_details_open),
                                ) { onAbout(it) }
                            }
                                ?: m
                        }.testTag("milkdrop.current"),
            )
            current?.let(selector.placeOf)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    onAbout: (String) -> Unit,
) {
    val list = rememberLazyListState()
    val all = selector.paths
    // a new list is checked ahead, so its rows can say what they will miss before one is picked
    LaunchedEffect(all) { selector.onShown(all) }
    var query by rememberSaveable { mutableStateOf("") }
    // what each preset is searched by, worked out once per list and off the main thread: a pack
    // can hold thousands
    val searched by produceState<List<String>?>(null, all) {
        value = withContext(Dispatchers.Default) { all.map { PresetSearch.text(PresetTitle.of(it), selector.placeOf(it)) } }
    }
    val words = PresetSearch.words(query)
    val paths =
        remember(all, searched, words) {
            val texts = searched
            if (words.isEmpty() || texts == null) all else all.filterIndexed { i, _ -> PresetSearch.matches(texts[i], words) }
        }
    // the list follows the preset playing when Next or Shuffle takes it out of view
    LaunchedEffect(selector.current, paths) {
        val index = paths.indexOf(selector.current)
        if (index < 0) return@LaunchedEffect
        val item = index + HEADER_ITEMS
        val shown = list.layoutInfo.visibleItemsInfo
        if (shown.none { it.index == item }) list.scrollToItem(item)
    }
    Column(Modifier.fillMaxSize()) {
        // above the list, not in it: the list opens scrolled to the preset playing
        if (all.size > 1) SearchField(query, onQuery = { query = it })
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("milkdrop.presets")) {
            item { Sources(prefs) }
            item { ListHeader(prefs, selector, found = paths.size.takeIf { words.isNotEmpty() }, query = query) }
            items(paths, key = { it }) { path ->
                PresetRow(
                    path = path,
                    playing = path == selector.current,
                    favorite = selector.isFavorite(path),
                    gaps = selector.gapsOf(path),
                    place = selector.placeOf(path),
                    onPlay = { selector.onPlay(path) },
                    onToggleFavorite = { selector.onToggleFavorite(path) },
                    onAbout = { onAbout(path) },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun PresetRow(
    path: String,
    playing: Boolean,
    favorite: Boolean,
    gaps: PresetGaps?,
    /** The folders it sits in, under its name. */
    place: String?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAbout: () -> Unit,
) {
    val name = File(path).nameWithoutExtension
    val title = PresetTitle.of(path)
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
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            place?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("milkdrop.preset.$name.place"),
                )
            }
            gaps?.let { PresetNotice.detailFor(it, words) }?.let { problem ->
                Problem(problem, gaps, Modifier.testTag("milkdrop.preset.$name.problem"))
            }
        }
        IconButton(onClick = onAbout, modifier = Modifier.testTag("milkdrop.preset.$name.about")) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.preset_details_open))
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

/** The page about the preset at [path], read off the main thread. */
@Composable
private fun AboutPreset(
    path: String,
    selector: PresetSelector,
    onPlayed: () -> Unit,
) {
    val details by produceState<PresetDetails?>(null, path) { value = withContext(Dispatchers.IO) { selector.describe(path) } }
    PresetDetailsPage(
        details = details,
        gaps = selector.gapsOf(path),
        favorite = selector.isFavorite(path),
        onPlay = {
            selector.onPlay(path)
            onPlayed()
        },
        onToggleFavorite = { selector.onToggleFavorite(path) },
        onForget = { selector.onForget(path) },
    )
}

/** The sources and the line about the list sit above the presets; the search field is outside the list. */
private const val HEADER_ITEMS = 2

/** The preview's height, at most this share of the room below the bar. */
private const val PREVIEW_H = 200
private const val PREVIEW_SHARE = 0.3f
