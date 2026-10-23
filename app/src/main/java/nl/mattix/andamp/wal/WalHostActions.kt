// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.menu.visualizationItems
import nl.mattix.andamp.ui.menu.visualizationMenu

/**
 * What the player does for a modern skin's buttons that reach outside the skin: each host action
 * and file request goes to one of these, on the main thread. A menu the skin asks for pops where
 * the finger last went down on the skin ([pressAt]).
 */
internal class WalHostActions(
    /** The main menu: the skin's menu, system menu and control menu buttons, and the main window's close button. */
    private val openMenu: (MenuAnchor?) -> Unit,
    private val minimize: () -> Unit,
    private val toggleLibrary: () -> Unit,
    /** The visualizer window, which shows Milkdrop or AVS as picked. */
    private val toggleVisualizer: () -> Unit,
    private val visualizationMenu: (MenuAnchor?) -> Unit,
    private val fullscreenVisualizer: () -> Unit,
    private val nextPreset: () -> Unit,
    private val previousPreset: () -> Unit,
    /** The file picker; true to play what is picked, for Play on an empty playlist. */
    private val pickFile: (andPlay: Boolean) -> Unit,
) : WalSink {
    /**
     * Where the last press on the skin went down: it makes the menu anchor there when a menu
     * opens, measured from the main window's rect of that moment. Null before the first press.
     */
    var pressAt: (() -> MenuAnchor)? = null

    override fun hostAction(
        action: String,
        param: String?,
    ) {
        when (action) {
            "MENU", "SYSMENU", "CONTROLMENU", "CLOSE" -> {
                openMenu(pressAt?.invoke())
            }

            "MINIMIZE" -> {
                minimize()
            }

            "VIS_MENU" -> {
                visualizationMenu(pressAt?.invoke())
            }

            "VIS_FS" -> {
                fullscreenVisualizer()
            }

            "VIS_NEXT" -> {
                nextPreset()
            }

            "VIS_PREV" -> {
                previousPreset()
            }

            // the windows the app shows; video and any other window are not there to show
            "TOGGLE" -> {
                when (param) {
                    "ml" -> toggleLibrary()
                    "avs" -> toggleVisualizer()
                    else -> Unit
                }
            }

            else -> {
                Unit
            }
        }
    }

    override fun openFile(andPlay: Boolean) = pickFile(andPlay)

    companion object {
        /**
         * The actions for [vm]'s player: the library and the visualizer window open and close as the
         * main menu opens them, the visualization menu grays the in-player modes a modern skin does
         * not show, and the preset buttons step Milkdrop only while its window is open.
         */
        fun of(
            vm: WinampViewModel,
            openMenu: (MenuAnchor?) -> Unit,
            onMinimize: () -> Unit,
            openFile: (andPlay: Boolean) -> Unit,
        ): WalHostActions {
            val s = vm.state
            return WalHostActions(
                openMenu = openMenu,
                minimize = onMinimize,
                toggleLibrary = {
                    s.setWindowOpen(WindowStore.LIBRARY, !s.libraryOpen) { on ->
                        if (on) vm.libraryOps.open() else s.libraryOpen = false
                    }
                },
                toggleVisualizer = { s.setWindowOpen(WindowStore.MILKDROP, !s.milkdropOn) { vm.presetOps.setWindowOpen(it) } },
                visualizationMenu = { anchor ->
                    s.activeMenu = visualizationMenu(vm, anchor).copy(items = visualizationItems(vm, modernPlayer = true))
                },
                // fullscreen shows only while the window is open too
                fullscreenVisualizer = {
                    s.setWindowOpen(WindowStore.MILKDROP, true) { vm.presetOps.setWindowOpen(it) }
                    s.milkdropFullscreen = true
                },
                nextPreset = { if (s.milkdropOn) s.visualCommands?.nextPreset() },
                previousPreset = { if (s.milkdropOn) s.visualCommands?.previousPreset() },
                pickFile = openFile,
            )
        }
    }
}
