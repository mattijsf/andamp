// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.presets.PresetDetails
import nl.mattix.andamp.state.presets.PresetTitle
import nl.mattix.andamp.state.presets.holding
import nl.mattix.andamp.visualizer.core.PresetGaps

/**
 * The preset list of [MilkdropScreen], handed to the screen as data: the presets of the source
 * playing, the one showing, and what can be done to them.
 */
data class PresetSelector(
    /** The source's presets by file, in the order the selector lists them. */
    val paths: List<String> = emptyList(),
    /** The file of the preset showing; null for the idle preset. */
    val current: String? = null,
    val currentName: String? = null,
    /** What the engine could not show of the preset showing. */
    val currentGaps: PresetGaps = PresetGaps.NONE,
    /** What is known of a preset's problems; null when it was not tried or checked yet. */
    val gapsOf: (String) -> PresetGaps? = { null },
    val isFavorite: (String) -> Boolean = { false },
    /** Plays a preset of [paths]; the engine stays on this source. */
    val onPlay: (String) -> Unit = {},
    val onToggleFavorite: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    /** The list a screen shows, for the engine to check ahead where it can. */
    val onShown: (List<String>) -> Unit = {},
    /** The line under a preset's name: the folders it sits in. */
    val placeOf: (String) -> String? = { null },
    /** Reads a preset for its page; blocking, so it is called off the main thread. */
    val describe: (String) -> PresetDetails? = { null },
    /** Forgets what went wrong with a preset, so it is no longer skipped. */
    val onForget: (String) -> Unit = {},
)

/** The selector's data, read off the view model. */
fun presetSelectorOf(vm: WinampViewModel): PresetSelector {
    val s = vm.state
    val ops = vm.presetOps
    val favorites = s.presetFavorites
    return PresetSelector(
        paths = ops.listing(if (favorites) null else s.presetPack),
        current = s.presetPath,
        currentName = s.presetPath?.let(PresetTitle::of) ?: s.presetName,
        currentGaps = s.presetGaps,
        gapsOf = { ops.health[it] },
        isFavorite = { ops.isFavorite(it) },
        onPlay = { ops.play(it, fromFavorites = favorites) },
        onToggleFavorite = { ops.toggleFavorite(it) },
        onNext = { s.visualCommands?.nextPreset() },
        onPrevious = { s.visualCommands?.previousPreset() },
        onShown = { ops.checkAhead(it) },
        placeOf = { PresetTitle.placeOf(it, ops.packs.holding(it)) },
        describe = { ops.describe(it) },
        onForget = { ops.forget(it) },
    )
}
