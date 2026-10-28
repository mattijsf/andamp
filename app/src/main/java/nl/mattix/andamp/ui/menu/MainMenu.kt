// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.R
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.SourceMark
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.state.shown
import nl.mattix.andamp.ui.prefs.SUPPORT_PAGE

/**
 * The titlebar options-button menu, the touch stand-in for Winamp's main menu (the top-left icon):
 * Play, file info, Bookmarks, the window toggles, then Options, Playback, Visualization and Skins,
 * and Exit. Media Library is Winamp 5's.
 */
@Suppress("LongParameterList") // one lambda per place the menu can send you
fun mainMenu(
    vm: WinampViewModel,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
    onPickSkin: () -> Unit,
    onExit: () -> Unit,
    onPreferences: () -> Unit = {},
    onMuseum: () -> Unit = {},
    /** Winamp's Ctrl+A, listed under Options as in Winamp. */
    onAlwaysOnTop: () -> Unit = {},
    /** Winamp's Ctrl+D, Double Size, listed under Options after Always On Top. */
    onDoubleSize: () -> Unit = {},
    /**
     * A modern skin's right-click menus that a long press offers, each named and opening its menu.
     * "Skin menu" then comes first, then a divider: one opens it directly, and several show as a
     * submenu by name. None gives no entry.
     */
    skinMenus: List<AmpMenuItem.Action> = emptyList(),
    /** Where the menu pops from: unless given, the options button in the titlebar, like Winamp's system-menu icon. */
    anchor: MenuAnchor = MenuAnchor(WindowStore.MAIN, OPTIONS_X, OPTIONS_Y, OPTIONS_W, OPTIONS_H),
    /**
     * A modern skin is worn. The player shows none of the classic windows and settings behind
     * Playlist Editor, Equalizer, Double Size and the in-player visualizer modes, so those entries
     * are grayed and unticked, and none of them changes unseen. The Milkdrop entries stay.
     */
    modernPlayer: Boolean = false,
): AmpMenu {
    val skins = skinItems(vm, onPickSkin, onMuseum)
    val bookmarks = bookmarksMenu(vm)
    val options = optionsItems(vm, onPreferences, skins, onAlwaysOnTop, onDoubleSize, modernPlayer)
    val playback = playbackItems(vm)
    return AmpMenu(
        vm.words.get(R.string.menu_winamp),
        listOfNotNull(
            // the skin's right-click menus, first and set apart; the entry names the menus, never
            // their entries, because a skin's menu has none until its script builds it
            skinMenuEntry(vm, skinMenus),
            AmpMenuItem.Divider.takeIf { skinMenus.isNotEmpty() },
            AmpMenuItem.Submenu(
                vm.words.get(R.string.menu_play),
                listOf(
                    // pickers, which belong to whoever hosts the menu rather
                    // than to the app: both hosts can register one
                    AmpMenuItem.Action(vm.words.get(R.string.menu_play_file), onClick = onOpenFile),
                    AmpMenuItem.Action(vm.words.get(R.string.menu_play_directory), onClick = onOpenFolder),
                    AmpMenuItem.Divider,
                    // Winamp lists them here to play one straight off - the
                    // bookmarks only, not the two entries that manage them
                    AmpMenuItem.Submenu(vm.words.get(R.string.menu_bookmark), bookmarkList(vm)),
                ),
            ),
            // Winamp's Alt+3: the item playing, or the last one that did in
            // this session - dark until something has been heard
            // not marked: it is a dialog, and AmpModals draws it wherever it is
            // hosted - including over a home screen, which is where the widget's
            // menu hosts it
            AmpMenuItem.Action(vm.words.get(R.string.menu_view_file_info), enabled = vm.state.lastPlayed != null) {
                vm.trackInfoOps.show(vm.state.lastPlayed)
            },
            AmpMenuItem.Submenu(vm.words.get(R.string.menu_bookmarks), bookmarks),
            AmpMenuItem.Divider,
            // Winamp ticks its open windows here. There is no Main Window entry because the player
            // cannot be closed, and no Minibrowser because there is none
            AmpMenuItem.Action(
                vm.words.get(R.string.menu_playlist_editor),
                enabled = !modernPlayer,
                checked = vm.state.plVisible && !modernPlayer,
                needsTheApp = true,
            ) {
                vm.state.setWindowOpen(WindowStore.PLAYLIST, !vm.state.plVisible) { vm.state.plVisible = it }
            },
            AmpMenuItem.Action(
                vm.words.get(R.string.menu_equalizer),
                enabled = !modernPlayer,
                checked = vm.state.eqVisible && !modernPlayer,
                needsTheApp = true,
            ) {
                vm.state.setWindowOpen(WindowStore.EQ, !vm.state.eqVisible) { vm.state.eqVisible = it }
            },
            // a window, ticked while it is on screen like the others here
            libraryItem(
                label = vm.words.get(R.string.menu_media_library),
                sources = vm.librarySources.present,
                showing = vm.librarySources.showing.takeIf { vm.state.libraryOpen && !vm.librarySources.showingAll },
                onToggle = {
                    // open() raises on its own; this keeps both directions in one shape
                    vm.state.setWindowOpen(WindowStore.LIBRARY, !vm.state.libraryOpen) { on ->
                        if (on) vm.libraryOps.open() else vm.state.libraryOpen = false
                    }
                },
                onSource = { source -> vm.sourceOps.libraryFrom(source, onSignIn = onPreferences) },
                named = { it.shown(vm.words) },
                all = vm.words.get(R.string.source_all).takeIf { vm.librarySources.usable.size > 1 },
                showingAll = vm.state.libraryOpen && vm.librarySources.showingAll,
                onAll = vm.sourceOps::libraryAll,
                markOf = { vm.state.sourceMarks[it.id] },
            ),
            AmpMenuItem.Divider,
            AmpMenuItem.Submenu(vm.words.get(R.string.menu_options), options),
            AmpMenuItem.Submenu(vm.words.get(R.string.menu_playback), playback),
            // every entry under it moves the player's own visualizer or opens the plug-in window,
            // so the whole branch is marked as needing the app
            AmpMenuItem.Submenu(
                vm.words.get(R.string.menu_visualization),
                visualizationItems(vm, modernPlayer),
                needsTheApp = true,
            ),
            AmpMenuItem.Submenu(vm.words.get(R.string.menu_skins), skins),
            AmpMenuItem.Divider,
            // Preferences, opened on the page with the tips; every host of the menu can open
            // Preferences
            AmpMenuItem.Action(vm.words.get(R.string.menu_support_andamp)) {
                vm.state.arrivalPage = SUPPORT_PAGE
                onPreferences()
            },
            AmpMenuItem.Divider,
            AmpMenuItem.Action(vm.words.get(R.string.menu_exit), onClick = onExit),
        ),
        anchor,
    )
}

/** "Skin menu" for [skinMenus]: the only one's action, a submenu naming several, or nothing for none. */
private fun skinMenuEntry(
    vm: WinampViewModel,
    skinMenus: List<AmpMenuItem.Action>,
): AmpMenuItem? {
    val label = vm.words.get(R.string.menu_skin_menu)
    return when (skinMenus.size) {
        0 -> null
        1 -> AmpMenuItem.Action(label, onClick = skinMenus.single().onClick)
        else -> AmpMenuItem.Submenu(label, skinMenus)
    }
}

/**
 * The entries of [visualizationMenu]. With a modern skin worn ([modernPlayer]) the in-player modes
 * are grayed, because the skin draws the player and a mode picked there would not show; the
 * Milkdrop entries stay.
 */
internal fun visualizationItems(
    vm: WinampViewModel,
    modernPlayer: Boolean,
): List<AmpMenuItem> {
    val items = visualizationMenu(vm).items
    if (!modernPlayer) return items
    val modes = vm.words.get(R.string.menu_visualization_options)
    return items.map { if (it is AmpMenuItem.Submenu && it.label == modes) it.copy(enabled = false) else it }
}

/**
 * Media Library, as one entry or as one per source.
 *
 * With one source it is a single entry. With more, each source is an entry in a submenu, the way
 * Winamp 5 lists its sources in the library's tree, under an entry for every source together
 * when [all] names it.
 *
 * [showing] is the source on screen, or null with the window closed or showing every source,
 * so the tick marks the open library; [showingAll] ticks the entry for every source. [label] is
 * what the entry is called.
 */
@Suppress("LongParameterList") // the entries, what is ticked, and what each does
internal fun libraryItem(
    label: String,
    sources: List<MusicSource>,
    showing: MusicSource?,
    onToggle: () -> Unit,
    onSource: (MusicSource) -> Unit,
    /** What a source is called in the submenu. */
    named: (MusicSource) -> String = { it.label },
    /** The entry for every source together, or null when it is not offered. */
    all: String? = null,
    showingAll: Boolean = false,
    onAll: () -> Unit = {},
    /** Each source's mark, beside its name in the submenu. */
    markOf: (MusicSource) -> SourceMark? = { null },
): AmpMenuItem {
    if (sources.size < 2) {
        return AmpMenuItem.Action(label, checked = showing != null, needsTheApp = true, onClick = onToggle)
    }
    return AmpMenuItem.Submenu(
        label,
        sourceItems(sources, showing, onSource, named, all, showingAll, onAll, markOf),
        needsTheApp = true,
    )
}

/** One entry per source, under one for every source together when [all] names it; see [libraryItem]. */
@Suppress("LongParameterList") // see libraryItem
internal fun sourceItems(
    sources: List<MusicSource>,
    showing: MusicSource?,
    onSource: (MusicSource) -> Unit,
    named: (MusicSource) -> String,
    all: String?,
    showingAll: Boolean,
    onAll: () -> Unit,
    /** Each source's mark, beside its name, as its rows carry it. */
    markOf: (MusicSource) -> SourceMark? = { null },
): List<AmpMenuItem> {
    val each =
        sources.map { source ->
            AmpMenuItem.Action(named(source), checked = source == showing, needsTheApp = true, mark = markOf(source)) {
                onSource(source)
            }
        }
    if (all == null) return each
    return listOf(AmpMenuItem.Action(all, checked = showingAll, needsTheApp = true, onClick = onAll), AmpMenuItem.Divider) + each
}

/**
 * Winamp's Playback menu, on its own so the widget's menu shows the same one. Every verb acts on
 * the player, not on a window.
 */
internal fun playbackItems(vm: WinampViewModel) =
    listOf(
        AmpMenuItem.Action(vm.words.get(R.string.menu_previous), onClick = vm::previous),
        AmpMenuItem.Action(vm.words.get(R.string.menu_play), onClick = vm::play),
        AmpMenuItem.Action(vm.words.get(R.string.menu_pause), onClick = vm::pause),
        AmpMenuItem.Action(vm.words.get(R.string.menu_stop), onClick = vm::stop),
        AmpMenuItem.Action(vm.words.get(R.string.menu_next), onClick = vm::next),
        AmpMenuItem.Divider,
        // Winamp's two other ways to stop, in its own order
        AmpMenuItem.Action(vm.words.get(R.string.menu_stop_with_fadeout), onClick = vm::stopWithFadeout),
        AmpMenuItem.Action(vm.words.get(R.string.menu_stop_after_current), checked = vm.state.stopAfterCurrent) {
            vm.setStopAfterCurrent(!vm.state.stopAfterCurrent)
        },
        AmpMenuItem.Divider,
        AmpMenuItem.Action(vm.words.get(R.string.menu_back_5_seconds)) { vm.seekBySeconds(-STEP_S) },
        AmpMenuItem.Action(vm.words.get(R.string.menu_fwd_5_seconds)) { vm.seekBySeconds(STEP_S) },
        AmpMenuItem.Divider,
        // dialogs, both of them, so they draw wherever the menu is hosted
        AmpMenuItem.Action(vm.words.get(R.string.menu_jump_to_time), onClick = vm::promptJumpToTime),
        AmpMenuItem.Action(vm.words.get(R.string.menu_jump_to_file), onClick = vm::promptJumpToFile),
        AmpMenuItem.Divider,
        AmpMenuItem.Action(vm.words.get(R.string.menu_start_of_list)) { vm.playTrack(0) },
        AmpMenuItem.Action(
            vm.words.get(R.string.menu_ten_tracks_back),
        ) { vm.playTrack((vm.state.currentIndex - TEN_BACK).coerceAtLeast(0)) },
    )

/**
 * Winamp's Left and Right arrows, as a menu item. The backend seeks in fractions of a track, so
 * this needs the track's length; a stream with no known length does not seek.
 */
private fun WinampViewModel.seekBySeconds(by: Int) {
    val length =
        state.playlist
            .getOrNull(state.currentIndex)
            ?.durationMs
            ?.div(MILLIS) ?: return
    if (length <= 0) return
    seekTo(((state.currentTimeSec + by).coerceIn(0, length.toInt()).toFloat() / length))
}

private const val STEP_S = 5

/** Winamp's own jump backwards through the queue. */
private const val TEN_BACK = 10

private const val MILLIS = 1000

private const val OPTIONS_X = 6
private const val OPTIONS_Y = 3
private const val OPTIONS_W = 9
private const val OPTIONS_H = 9

/**
 * Winamp's Options menu, as the clutter bar's O opens it: the same list the root menu shows under
 * Options.
 */
@Suppress("LongParameterList") // one lambda per thing only the screen can do
fun optionsMenu(
    vm: WinampViewModel,
    anchor: MenuAnchor? = null,
    onPreferences: () -> Unit = {},
    onPickSkin: () -> Unit = {},
    onMuseum: () -> Unit = {},
    /** Winamp's Ctrl+A; the same switch the clutter bar's A throws. */
    onAlwaysOnTop: () -> Unit = {},
    /** Winamp's Ctrl+D; the same switch the clutter bar's D throws. */
    onDoubleSize: () -> Unit = {},
    /** Grays Double Size while a modern skin is worn; see [mainMenu]. */
    modernPlayer: Boolean = false,
): AmpMenu =
    AmpMenu(
        vm.words.get(R.string.menu_options),
        optionsItems(vm, onPreferences, skinItems(vm, onPickSkin, onMuseum), onAlwaysOnTop, onDoubleSize, modernPlayer),
        anchor,
    )

/**
 * Winamp's Skins submenu: the browser, the picker, "Color themes" for the modern skin on screen,
 * and every skin in the library.
 *
 * "Color themes" lists the skin's themes in the order the skin's theme list shows them, as the skin
 * names them, the one it wears ticked, each putting that theme on. It is left out with a classic
 * skin worn, with a modern one parked under a music source's skin, before the skin has loaded, and
 * for a skin with fewer than two themes. It is marked as needing the app: a theme shows only on the
 * player.
 */
internal fun skinItems(
    vm: WinampViewModel,
    onPickSkin: () -> Unit,
    onMuseum: () -> Unit,
): List<AmpMenuItem> =
    buildList {
        add(
            AmpMenuItem.Action(vm.words.get(R.string.menu_skin_browser), needsTheApp = true) {
                vm.state.skinManagerOpen = true
                vm.state.raiseWindow(WindowStore.SKINS)
            },
        )
        // Winamp's browser is a folder of skins; on a phone a skin arrives
        // through the picker, so this is what "put one in the folder" means
        // not marked either: the picker belongs to whoever hosts the menu, and
        // both hosts can open one
        add(AmpMenuItem.Action(vm.words.get(R.string.menu_load_skin), onClick = onPickSkin))
        vm.skinOps.wal?.takeIf { it.themes.size >= MIN_THEMES }?.let { look ->
            add(
                AmpMenuItem.Submenu(
                    vm.words.get(R.string.menu_color_themes),
                    look.themes.map { name ->
                        AmpMenuItem.Action(name.take(MAX_THEME_NAME), checked = name.equals(look.theme, ignoreCase = true)) {
                            look.pickTheme(name)
                        }
                    },
                    needsTheApp = true,
                ),
            )
        }
        add(AmpMenuItem.Divider)
        add(
            // Winamp's <Base Skin>, under the name of the skin the app ships with and falls back to
            AmpMenuItem.Action(SkinEntry.BASE_NAME, checked = vm.skinOps.currentId == SkinEntry.BASE_ID) {
                vm.skinOps.reset()
            },
        )
        // Winamp's own words, in Winamp's own place for them
        // not marked as needing the app: the museum is a screen of its own, so
        // like Preferences it is somewhere the widget's menu can send you and
        // come back from, rather than something only the player can do
        add(AmpMenuItem.Action(vm.words.get(R.string.menu_get_more_skins), onClick = onMuseum))
        // the library survives restarts, so past skins stay one tap away
        vm.state.skinEntries.filterNot { it.id == SkinEntry.BASE_ID }.forEach { entry ->
            add(
                AmpMenuItem.Action(entry.label(vm.words), checked = entry.id == vm.skinOps.currentId) {
                    vm.skinOps.apply(entry.id)
                },
            )
        }
    }

/** The fewest color themes a skin has for "Color themes" to be offered: with one there is nothing to pick. */
private const val MIN_THEMES = 2

/** Characters shown of a color theme's name, as many as a skin's menus show of an entry's words. */
private const val MAX_THEME_NAME = 128

/** The Options list itself, shown under Options and behind the clutter bar's O. */
private fun optionsItems(
    vm: WinampViewModel,
    onPreferences: () -> Unit,
    skins: List<AmpMenuItem>,
    onAlwaysOnTop: () -> Unit,
    onDoubleSize: () -> Unit,
    /** Grays Double Size while a modern skin is worn; see [mainMenu]. */
    modernPlayer: Boolean = false,
): List<AmpMenuItem> =
    listOfNotNull(
        AmpMenuItem.Action(vm.words.get(R.string.menu_preferences), onClick = onPreferences),
        AmpMenuItem.Submenu(vm.words.get(R.string.menu_skins), skins),
        skinSettingsItem(vm),
        AmpMenuItem.Divider,
        AmpMenuItem.Action(vm.words.get(R.string.menu_time_elapsed), checked = !vm.state.timeRemaining) {
            vm.state.timeRemaining =
                false
        },
        AmpMenuItem.Action(vm.words.get(R.string.menu_time_remaining), checked = vm.state.timeRemaining) {
            vm.state.timeRemaining =
                true
        },
        AmpMenuItem.Divider,
        // Winamp's own group, minus EasyMove: every window here already moves with a finger
        AmpMenuItem.Action(
            vm.words.get(R.string.menu_always_on_top),
            checked = vm.state.alwaysOnTop,
            needsTheApp = true,
            onClick = onAlwaysOnTop,
        ),
        AmpMenuItem.Action(
            vm.words.get(R.string.menu_double_size),
            enabled = !modernPlayer,
            checked = vm.state.doubleSize && !modernPlayer,
            needsTheApp = true,
            onClick = onDoubleSize,
        ),
        AmpMenuItem.Divider,
        AmpMenuItem.Action(vm.words.get(R.string.menu_repeat), checked = vm.state.repeat) { vm.toggleRepeat() },
        AmpMenuItem.Action(vm.words.get(R.string.menu_shuffle), checked = vm.state.shuffle) { vm.toggleShuffle() },
    )
