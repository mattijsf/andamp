// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import nl.mattix.andamp.state.WinampViewModel

/**
 * The plug-in's settings, read off the view model. One builder for the two screens that show them:
 * the Visualizer page inside Preferences and [MilkdropScreen], which the visual's long press opens.
 * That screen's preset list is [presetSelectorOf].
 *
 * @param onManagePresets where "browse the presets" goes, or null on the screen that already is
 * that place.
 */
fun visualizerPrefsOf(
    vm: WinampViewModel,
    onManagePresets: (() -> Unit)? = null,
) = VisualizerPrefs(
    plugin = vm.state.visPlugin,
    onPlugin = { vm.presetOps.selectPlugin(it) },
    shuffle = vm.state.presetShuffle,
    onShuffle = { vm.presetOps.setShuffle(it) },
    activePack = vm.state.presetPack,
    onSelectPack = { vm.presetOps.select(it) },
    favoritesOn = vm.state.presetFavorites,
    favoriteCount = vm.presetOps.favoriteFiles(vm.state.visPlugin).size,
    onSelectFavorites = { vm.presetOps.selectFavorites() },
    packs = vm.presetOps.packsFor(vm.state.visPlugin),
    onDeletePack = { vm.presetOps.remove(it) },
    // the preset picker is launched by the player's screen, which watches this flag
    onImportPack = { vm.state.presetPickRequested = true },
    importing = vm.presetOps.importing,
    presets = vm.state.presetNames,
    onManagePresets = onManagePresets,
)
