// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.R
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.presets.InstalledPack

/**
 * Everything the running plug-in can be told. Reached two ways with the same entries: a long press
 * on the visual, and "Configure plug-in" in Winamp's Visualization menu.
 */
fun visualMenu(
    vm: WinampViewModel,
    anchor: MenuAnchor? = null,
): AmpMenu = AmpMenu(vm.state.visPlugin.menuLabel, visualMenuItems(vm), anchor)

/** The same entries, for a menu that hosts them as a submenu. */
fun visualMenuItems(vm: WinampViewModel): List<AmpMenuItem> {
    val s = vm.state
    return buildList {
        add(AmpMenuItem.Submenu(vm.words.get(R.string.menu_presets), presetItems(vm)))
        add(AmpMenuItem.Submenu(vm.words.get(R.string.menu_preset_pack), packItems(vm)))
        add(AmpMenuItem.Action(vm.words.get(R.string.menu_next_preset)) { s.visualCommands?.nextPreset() })
        add(AmpMenuItem.Action(vm.words.get(R.string.menu_previous_preset)) { s.visualCommands?.previousPreset() })
        // the idle preset is no file, so it cannot be a favorite
        s.presetPath?.let { path ->
            val favorite = vm.presetOps.isFavorite(path)
            add(
                AmpMenuItem.Action(vm.words.get(if (favorite) R.string.menu_remove_favorite else R.string.menu_add_favorite)) {
                    vm.presetOps.toggleFavorite(path)
                },
            )
        }
        add(AmpMenuItem.Divider)
        add(
            AmpMenuItem.Action(vm.words.get(R.string.menu_shuffle), checked = s.presetShuffle) {
                vm.presetOps.setShuffle(!s.presetShuffle)
            },
        )
        add(
            AmpMenuItem.Action(vm.words.get(R.string.menu_show_preset_missing), checked = s.presetNotices) {
                vm.presetOps.setNotices(!s.presetNotices)
            },
        )
        add(AmpMenuItem.Divider)
        add(AmpMenuItem.Action(vm.words.get(R.string.menu_manage_presets)) { s.presetManagerRequested = true })
        add(AmpMenuItem.Action(vm.words.get(R.string.menu_load_preset_pack)) { s.presetPickRequested = true })
        add(AmpMenuItem.Divider)
        add(
            AmpMenuItem.Action(vm.words.get(R.string.menu_fullscreen), checked = s.milkdropFullscreen) {
                s.milkdropFullscreen = !s.milkdropFullscreen
            },
        )
        add(AmpMenuItem.Action(vm.words.get(R.string.menu_close)) { vm.presetOps.setWindowOpen(false) })
    }
}

/**
 * Which pack is loaded, from the packs this engine can run something from. The engine's own idle
 * preset is always an option because it needs no import. The favorites are one more, played
 * like a pack, above the rest once there are any.
 */
private fun packItems(vm: WinampViewModel): List<AmpMenuItem> {
    val s = vm.state
    val favorites = vm.presetOps.favoriteFiles(s.visPlugin).size
    return buildList {
        if (favorites > 0) {
            add(
                AmpMenuItem.Action(vm.words.get(R.string.menu_favorites_with_count, favorites), checked = s.presetFavorites) {
                    vm.presetOps.selectFavorites()
                    vm.presetOps.setWindowOpen(true)
                },
            )
            add(AmpMenuItem.Divider)
        }
        add(
            AmpMenuItem.Action(vm.words.get(s.visPlugin.idleLabelRes), checked = !s.presetFavorites && s.presetPack == null) {
                vm.presetOps.select(null)
                vm.presetOps.setWindowOpen(true)
            },
        )
        vm.presetOps.packsFor(s.visPlugin).forEach { pack ->
            add(
                AmpMenuItem.Action(
                    vm.words.get(R.string.menu_pack_with_count, pack.name, pack.countFor(s.visPlugin)),
                    checked = !s.presetFavorites && s.presetPack == pack.name,
                ) {
                    vm.presetOps.select(pack.name)
                    vm.presetOps.setWindowOpen(true)
                },
            )
        }
    }
}

/** What the no-import option is called, as a string resource: each engine has a built-in one. */
val VisPlugin.idleLabelRes: Int
    get() =
        when (this) {
            VisPlugin.Avs -> R.string.menu_avs_idle_preset
            VisPlugin.Milkdrop -> R.string.menu_projectm_idle_preset
        }

/** How many presets of [plugin]'s kind a pack holds. */
fun InstalledPack.countFor(plugin: VisPlugin): Int =
    when (plugin) {
        VisPlugin.Avs -> avsCount
        VisPlugin.Milkdrop -> presetCount
    }

/**
 * The presets themselves, so one can be picked without leaving the visual. Capped at
 * [PRESET_MENU_MAX]; past the cap a last entry opens the preset manager.
 */
private fun presetItems(vm: WinampViewModel): List<AmpMenuItem> {
    val s = vm.state
    if (s.presetNames.isEmpty()) {
        return listOf(AmpMenuItem.Action(vm.words.get(R.string.menu_no_presets_loaded)) { s.presetManagerRequested = true })
    }
    return buildList {
        s.presetNames.take(PRESET_MENU_MAX).forEachIndexed { index, name ->
            add(
                AmpMenuItem.Action(name, checked = name == s.presetName) {
                    // picked by hand, so a preset that is skipped is tried again
                    val path = s.presetPaths.getOrNull(index)
                    if (path !=
                        null
                    ) {
                        vm.presetOps.play(path, fromFavorites = s.presetFavorites)
                    } else {
                        s.visualCommands?.goToPreset(index)
                    }
                },
            )
        }
        if (s.presetNames.size > PRESET_MENU_MAX) {
            add(AmpMenuItem.Divider)
            add(
                AmpMenuItem.Action(vm.words.get(R.string.menu_all_presets, s.presetNames.size)) {
                    s.presetManagerRequested = true
                },
            )
        }
    }
}

/** How many presets the menu lists before it points to the manager. */
internal const val PRESET_MENU_MAX = 30
