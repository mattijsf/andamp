// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.R
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
        vm.words.get(R.string.menu_visualization),
        listOf(
            // the options dialog's actual content: what the in-player display shows
            AmpMenuItem.Submenu(
                vm.words.get(R.string.menu_visualization_options),
                VisMode.entries.map { mode -> AmpMenuItem.Action(vm.words.get(mode.menuLabelRes)) { s.visMode = mode } },
            ),
            AmpMenuItem.Divider,
            AmpMenuItem.Action(vm.words.get(R.string.menu_start_stop_plug_in)) {
                s.setWindowOpen(nl.mattix.andamp.state.WindowStore.MILKDROP, !s.milkdropOn) {
                    vm.presetOps.setWindowOpen(it)
                }
            },
            // the plug-in's configuration is the same list its own long press offers
            AmpMenuItem.Submenu(vm.words.get(R.string.menu_configure_plug_in), visualMenuItems(vm)),
            AmpMenuItem.Submenu(
                vm.words.get(R.string.menu_select_plug_in),
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

/** What a mode is called in a menu, as a string resource. */
internal val VisMode.menuLabelRes: Int
    get() =
        when (this) {
            VisMode.Analyzer -> R.string.menu_spectrum_analyzer
            VisMode.Oscilloscope -> R.string.menu_oscilloscope
            VisMode.Off -> R.string.menu_off
        }

/** The same where there is no room for the long name: "Analyzer" for "Spectrum analyzer". */
internal val VisMode.shortLabelRes: Int
    get() =
        when (this) {
            VisMode.Analyzer -> R.string.menu_analyzer
            VisMode.Oscilloscope -> R.string.menu_oscilloscope
            VisMode.Off -> R.string.menu_off
        }
