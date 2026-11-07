// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.menu.MenuBarSection
import nl.mattix.andamp.ui.menu.visualizationItems
import nl.mattix.andamp.ui.menu.visualizationMenu
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.playlistMenus

/**
 * What the player does for a modern skin's buttons that reach outside the skin: each host action
 * and file request goes to one of these, on the main thread. A menu the skin asks for pops where
 * the finger last went down on the skin ([pressAt]).
 */
@Suppress("LongParameterList") // one for each thing outside the skin its buttons reach
internal class WalHostActions(
    /**
     * The main menu: the skin's menu, system menu and control menu buttons; a menu bar entry opens
     * the part it names, when the main menu has it.
     */
    private val openMenu: (MenuBarSection?, MenuAnchor?) -> Unit,
    private val minimize: () -> Unit,
    /** The main window's close button: stops the music and closes the player, as the classic X does. */
    private val exit: () -> Unit,
    private val toggleLibrary: () -> Unit,
    /** The visualizer window, which shows Milkdrop or AVS as picked. */
    private val toggleVisualizer: () -> Unit,
    /** Opens the visualizer window, as Winamp Modern's Detach Visualizer and Detach Video move it out of the drawer. */
    private val showVisualizer: () -> Unit,
    private val visualizationMenu: (MenuAnchor?) -> Unit,
    private val fullscreenVisualizer: () -> Unit,
    private val nextPreset: () -> Unit,
    private val previousPreset: () -> Unit,
    /** The file picker; true to play what is picked, for Play on an empty playlist. */
    private val pickFile: (andPlay: Boolean) -> Unit,
    /** View file info for the song playing, as the classic clutter bar's I opens it. */
    private val fileInfo: () -> Unit,
    /** Always On Top on or off, as the classic clutter bar's A switches it. */
    private val alwaysOnTop: () -> Unit = {},
    /** The clutter bar's D: the classic skin fills the screen, or its windows go apart again. */
    private val doubleSize: () -> Unit = {},
    /** A pick from a classic playlist's bottom-button menus, with where the finger went down. */
    private val playlistPick: (pick: String, anchor: MenuAnchor?) -> Unit = { _, _ -> },
    /** The classic playlist's selected row, or null for none. */
    private val selectRow: (Int?) -> Unit = {},
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
        when (val a = HostAction.of(action, param)) {
            is HostAction.Menu -> {
                openMenu(MenuBarSection.of(a.section), pressAt?.invoke())
            }

            HostAction.Close -> {
                exit()
            }

            HostAction.Minimize -> {
                minimize()
            }

            HostAction.VisualizationMenu -> {
                visualizationMenu(pressAt?.invoke())
            }

            // the video's too: Andamp plays no video, and the skin's video area shows the visualizer
            HostAction.Fullscreen -> {
                fullscreenVisualizer()
            }

            HostAction.NextPreset -> {
                nextPreset()
            }

            HostAction.PreviousPreset -> {
                previousPreset()
            }

            HostAction.TrackInfo -> {
                fileInfo()
            }

            HostAction.AlwaysOnTop, HostAction.DoubleSize -> {
                clutterBar(a)
            }

            is HostAction.Playlist, is HostAction.PlaylistSelect -> {
                playlist(a)
            }

            // the video too: Andamp plays no video, and the skin's video area shows the visualizer
            is HostAction.Show -> {
                if (a.window == "avs" || a.window == "video") showVisualizer()
            }

            // the windows the app shows; video and any other window are not there to show
            is HostAction.Toggle -> {
                when (a.window) {
                    "ml" -> toggleLibrary()
                    "avs" -> toggleVisualizer()
                    else -> Unit
                }
            }

            null -> {
                Unit
            }
        }
    }

    /** A classic clutter bar's A (Always On Top) or D (here: fill the screen). */
    private fun clutterBar(a: HostAction) {
        if (a == HostAction.DoubleSize) doubleSize() else alwaysOnTop()
    }

    /** A classic playlist's pick, at the last press, or the row it selected. */
    private fun playlist(a: HostAction) {
        when (a) {
            is HostAction.Playlist -> playlistPick(a.pick, pressAt?.invoke())
            is HostAction.PlaylistSelect -> selectRow(a.row)
            else -> Unit
        }
    }

    override fun openFile(andPlay: Boolean) = pickFile(andPlay)

    companion object {
        /**
         * The actions for [vm]'s player: the library and the visualizer window open and close as the
         * main menu opens them, the visualization menu grays the in-player modes a modern skin does
         * not show, fullscreen and the preset buttons reach the visualizer only while its window or
         * the skin shows it ([WinampState.visInSkin]), file info shows the song playing, or the
         * last one that played, and a classic playlist's picks do what the same entries of the
         * app's own playlist menus do ([playlistMenus], with [playlist]'s pickers), on the row the
         * skin selected.
         */
        @Suppress("LongParameterList") // one for each thing outside the skin its buttons reach
        fun of(
            vm: WinampViewModel,
            openMenu: (MenuBarSection?, MenuAnchor?) -> Unit,
            onMinimize: () -> Unit,
            openFile: (andPlay: Boolean) -> Unit,
            onExit: () -> Unit = {},
            onAlwaysOnTop: () -> Unit = {},
            playlist: () -> PlaylistMenuActions = { PlaylistMenuActions() },
        ): WalHostActions {
            val s = vm.state
            return WalHostActions(
                openMenu = openMenu,
                minimize = onMinimize,
                exit = onExit,
                toggleLibrary = {
                    s.setWindowOpen(WindowStore.LIBRARY, !s.libraryOpen) { on ->
                        if (on) vm.libraryOps.open() else s.libraryOpen = false
                    }
                },
                toggleVisualizer = { s.setWindowOpen(WindowStore.MILKDROP, !s.milkdropOn) { vm.presetOps.setWindowOpen(it) } },
                showVisualizer = { s.setWindowOpen(WindowStore.MILKDROP, true) { vm.presetOps.setWindowOpen(it) } },
                visualizationMenu = { anchor ->
                    s.activeMenu = visualizationMenu(vm, anchor).copy(items = visualizationItems(vm, modernPlayer = true))
                },
                // fullscreen shows only while the window or the skin shows the visualizer
                fullscreenVisualizer = {
                    if (!s.visInSkin) s.setWindowOpen(WindowStore.MILKDROP, true) { vm.presetOps.setWindowOpen(it) }
                    s.milkdropFullscreen = true
                },
                nextPreset = { if (s.milkdropOn || s.visInSkin) s.visualCommands?.nextPreset() },
                previousPreset = { if (s.milkdropOn || s.visInSkin) s.visualCommands?.previousPreset() },
                pickFile = openFile,
                fileInfo = { vm.trackInfoOps.show(s.lastPlayed) },
                alwaysOnTop = onAlwaysOnTop,
                doubleSize = { WalFilled.toggle(vm.getApplication()) },
                playlistPick = { pick, anchor ->
                    val id = PLAYLIST_ENTRIES[pick]
                    val entry = playlistMenus(playlist()).flatMap { it.entries }.firstOrNull { it.id == id }
                    entry?.action?.invoke(vm)
                    // a submenu (Sort list) opens where the finger went down on the skin
                    if (anchor != null && pick == "sort") s.activeMenu = s.activeMenu?.copy(anchor = anchor)
                },
                selectRow = { row -> if (row == null) vm.playlistOps.selectZero() else vm.playlistOps.selectTrack(row) },
            )
        }

        /** The entry of the app's own playlist menus ([playlistMenus]) each of walkit's classic playlist picks stands for. */
        val PLAYLIST_ENTRIES =
            mapOf(
                "add_url" to "pl.menu.add.url",
                "add_dir" to "pl.menu.add.dir",
                "add_file" to "pl.menu.add.file",
                "remove_misc" to "pl.menu.rem.misc",
                "remove_all" to "pl.menu.rem.all",
                "crop" to "pl.menu.rem.crop",
                "remove_selected" to "pl.menu.rem.selected",
                "select_invert" to "pl.menu.sel.invert",
                "select_none" to "pl.menu.sel.zero",
                "select_all" to "pl.menu.sel.all",
                "sort" to "pl.menu.misc.sort",
                "file_info" to "pl.menu.misc.info",
                "misc_options" to "pl.menu.misc.opts",
                "list_new" to "pl.menu.list.new",
                "list_save" to "pl.menu.list.save",
                "list_load" to "pl.menu.list.load",
            )
    }
}
