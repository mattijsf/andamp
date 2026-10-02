// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

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
        add(AmpMenuItem.Submenu("Presets", presetItems(vm)))
        add(AmpMenuItem.Submenu("Preset pack", packItems(vm)))
        add(AmpMenuItem.Action("Next preset") { s.visualCommands?.nextPreset() })
        add(AmpMenuItem.Action("Previous preset") { s.visualCommands?.previousPreset() })
        add(AmpMenuItem.Divider)
        add(
            AmpMenuItem.Action("Shuffle", checked = s.presetShuffle) {
                vm.presetOps.setShuffle(!s.presetShuffle)
            },
        )
        add(AmpMenuItem.Divider)
        add(AmpMenuItem.Action("Manage presets...") { s.presetManagerRequested = true })
        add(AmpMenuItem.Action("Load preset pack...") { s.presetPickRequested = true })
        add(AmpMenuItem.Divider)
        add(
            AmpMenuItem.Action("Fullscreen", checked = s.milkdropFullscreen) {
                s.milkdropFullscreen = !s.milkdropFullscreen
            },
        )
        add(AmpMenuItem.Action("Close") { vm.presetOps.setWindowOpen(false) })
    }
}

/**
 * Which pack is loaded, from the packs this engine can run something from. The engine's own idle
 * preset is always an option because it needs no import.
 */
private fun packItems(vm: WinampViewModel): List<AmpMenuItem> {
    val s = vm.state
    return buildList {
        add(
            AmpMenuItem.Action(s.visPlugin.idleLabel, checked = s.presetPack == null) {
                vm.presetOps.select(null)
                vm.presetOps.setWindowOpen(true)
            },
        )
        vm.presetOps.packsFor(s.visPlugin).forEach { pack ->
            add(
                AmpMenuItem.Action("${pack.name} (${pack.countFor(s.visPlugin)})", checked = s.presetPack == pack.name) {
                    vm.presetOps.select(pack.name)
                    vm.presetOps.setWindowOpen(true)
                },
            )
        }
    }
}

/** What the no-import option is called: each engine has its own built-in. */
val VisPlugin.idleLabel: String
    get() =
        when (this) {
            VisPlugin.Avs -> "AVS idle preset"
            VisPlugin.Milkdrop -> "projectM idle preset"
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
        return listOf(AmpMenuItem.Action("No presets loaded") { s.presetManagerRequested = true })
    }
    return buildList {
        s.presetNames.take(PRESET_MENU_MAX).forEachIndexed { index, name ->
            add(AmpMenuItem.Action(name, checked = name == s.presetName) { s.visualCommands?.goToPreset(index) })
        }
        if (s.presetNames.size > PRESET_MENU_MAX) {
            add(AmpMenuItem.Divider)
            add(
                AmpMenuItem.Action("All ${s.presetNames.size} presets...") {
                    s.presetManagerRequested = true
                },
            )
        }
    }
}

/** How many presets the menu lists before it points to the manager. */
internal const val PRESET_MENU_MAX = 30
