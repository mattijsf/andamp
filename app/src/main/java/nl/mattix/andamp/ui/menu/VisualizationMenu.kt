// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.WinampViewModel

/**
 * Winamp's visualization context menu, what right-clicking the visualizer opened in 2.x:
 *
 * ```
 * Visualization options  >
 * ---
 * Start/Stop plug-in
 * Configure plug-in      >
 * Select plug-in         >
 * ```
 *
 * Where Winamp opened a dialog, this opens a submenu with that dialog's content, so the entries
 * carry no trailing "...". The keyboard accelerators are left out.
 */
fun visualizationMenu(
    vm: WinampViewModel,
    anchor: MenuAnchor? = null,
): AmpMenu {
    val s = vm.state
    return AmpMenu(
        "Visualization",
        listOf(
            // the options dialog's actual content: what the in-player display shows
            AmpMenuItem.Submenu(
                "Visualization options",
                VisMode.entries.map { mode -> AmpMenuItem.Action(mode.menuLabel()) { s.visMode = mode } },
            ),
            AmpMenuItem.Divider,
            AmpMenuItem.Action("Start/Stop plug-in") {
                s.setWindowOpen(nl.mattix.andamp.state.WindowStore.MILKDROP, !s.milkdropOn) {
                    vm.presetOps.setWindowOpen(it)
                }
            },
            // the plug-in's configuration is the same list its own long press offers
            AmpMenuItem.Submenu("Configure plug-in", visualMenuItems(vm)),
            AmpMenuItem.Submenu(
                "Select plug-in",
                VisPlugin.entries.map { plugin ->
                    AmpMenuItem.Action(plugin.menuLabel, checked = s.visPlugin == plugin) {
                        vm.presetOps.selectPlugin(plugin)
                        vm.presetOps.setWindowOpen(true)
                    }
                },
            ),
        ),
        anchor,
    )
}

internal fun VisMode.menuLabel(): String =
    when (this) {
        VisMode.Analyzer -> "Spectrum analyzer"
        VisMode.Oscilloscope -> "Oscilloscope"
        VisMode.Off -> "Off"
    }

/** The same names where there is no room for the long one: "Analyzer" for "Spectrum analyzer". */
internal fun VisMode.shortLabel(): String =
    when (this) {
        VisMode.Analyzer -> "Analyzer"
        VisMode.Oscilloscope -> "Oscilloscope"
        VisMode.Off -> "Off"
    }
