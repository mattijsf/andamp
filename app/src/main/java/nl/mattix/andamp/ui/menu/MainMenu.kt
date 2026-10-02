// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore

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
): AmpMenu {
    val skins = skinItems(vm, onPickSkin, onMuseum)
    val bookmarks = bookmarksMenu(vm)
    val options = optionsItems(vm, onPreferences, skins, onAlwaysOnTop)
    val playback = playbackItems(vm)
    return AmpMenu(
        "Winamp",
        listOf(
            AmpMenuItem.Submenu(
                "Play",
                listOf(
                    // pickers, which belong to whoever hosts the menu rather
                    // than to the app: both hosts can register one
                    AmpMenuItem.Action("Play file...", onClick = onOpenFile),
                    AmpMenuItem.Action("Play directory...", onClick = onOpenFolder),
                    AmpMenuItem.Divider,
                    // Winamp lists them here to play one straight off - the
                    // bookmarks only, not the two entries that manage them
                    AmpMenuItem.Submenu("Bookmark", bookmarkList(vm)),
                ),
            ),
            // Winamp's Alt+3: the item playing, or the last one that did in
            // this session - dark until something has been heard
            // not marked: it is a dialog, and AmpModals draws it wherever it is
            // hosted - including over a home screen, which is where the widget's
            // menu hosts it
            AmpMenuItem.Action("View file info", enabled = vm.state.lastPlayed != null) {
                vm.trackInfoOps.show(vm.state.lastPlayed)
            },
            AmpMenuItem.Submenu("Bookmarks", bookmarks),
            AmpMenuItem.Divider,
            // Winamp ticks its open windows here. There is no Main Window entry because the player
            // cannot be closed, and no Minibrowser because there is none
            AmpMenuItem.Action("Playlist Editor", checked = vm.state.plVisible, needsTheApp = true) {
                vm.state.setWindowOpen(WindowStore.PLAYLIST, !vm.state.plVisible) { vm.state.plVisible = it }
            },
            AmpMenuItem.Action("Equalizer", checked = vm.state.eqVisible, needsTheApp = true) {
                vm.state.setWindowOpen(WindowStore.EQ, !vm.state.eqVisible) { vm.state.eqVisible = it }
            },
            // a window, ticked while it is on screen like the others here
            libraryItem(
                sources = vm.librarySources.present,
                showing = vm.librarySources.showing.takeIf { vm.state.libraryOpen },
                onToggle = {
                    // open() raises on its own; this keeps both directions in one shape
                    vm.state.setWindowOpen(WindowStore.LIBRARY, !vm.state.libraryOpen) { on ->
                        if (on) vm.libraryOps.open() else vm.state.libraryOpen = false
                    }
                },
                onSource = { source -> vm.sourceOps.libraryFrom(source, onSignIn = onPreferences) },
            ),
            AmpMenuItem.Divider,
            AmpMenuItem.Submenu("Options", options),
            AmpMenuItem.Submenu("Playback", playback),
            // every entry under it moves the player's own visualizer or opens the plug-in window,
            // so the whole branch is marked as needing the app
            AmpMenuItem.Submenu("Visualization", visualizationMenu(vm).items, needsTheApp = true),
            AmpMenuItem.Submenu("Skins", skins),
            AmpMenuItem.Divider,
            AmpMenuItem.Action("Exit", onClick = onExit),
        ),
        // the options button in the titlebar, like Winamp's system-menu icon
        MenuAnchor(WindowStore.MAIN, OPTIONS_X, OPTIONS_Y, OPTIONS_W, OPTIONS_H),
    )
}

/**
 * Media Library, as one entry or as one per source.
 *
 * With one source it is a single entry. With more, each source is an entry in a submenu, the way
 * Winamp 5 lists its sources in the library's tree.
 *
 * [showing] is the source on screen, or null with the window closed, so the tick marks the open
 * library.
 */
internal fun libraryItem(
    sources: List<MusicSource>,
    showing: MusicSource?,
    onToggle: () -> Unit,
    onSource: (MusicSource) -> Unit,
): AmpMenuItem {
    if (sources.size < 2) {
        return AmpMenuItem.Action(LIBRARY, checked = showing != null, needsTheApp = true, onClick = onToggle)
    }
    return AmpMenuItem.Submenu(
        LIBRARY,
        sources.map { source ->
            AmpMenuItem.Action(source.label, checked = source == showing, needsTheApp = true) { onSource(source) }
        },
        needsTheApp = true,
    )
}

private const val LIBRARY = "Media Library"

/**
 * Winamp's Playback menu, on its own so the widget's menu shows the same one. Every verb acts on
 * the player, not on a window.
 */
internal fun playbackItems(vm: WinampViewModel) =
    listOf(
        AmpMenuItem.Action("Previous", onClick = vm::previous),
        AmpMenuItem.Action("Play", onClick = vm::play),
        AmpMenuItem.Action("Pause", onClick = vm::pause),
        AmpMenuItem.Action("Stop", onClick = vm::stop),
        AmpMenuItem.Action("Next", onClick = vm::next),
        AmpMenuItem.Divider,
        // Winamp's two other ways to stop, in its own order
        AmpMenuItem.Action("Stop with fadeout", onClick = vm::stopWithFadeout),
        AmpMenuItem.Action("Stop after current", checked = vm.state.stopAfterCurrent) {
            vm.setStopAfterCurrent(!vm.state.stopAfterCurrent)
        },
        AmpMenuItem.Divider,
        AmpMenuItem.Action("Back 5 seconds") { vm.seekBySeconds(-STEP_S) },
        AmpMenuItem.Action("Fwd 5 seconds") { vm.seekBySeconds(STEP_S) },
        AmpMenuItem.Divider,
        // dialogs, both of them, so they draw wherever the menu is hosted
        AmpMenuItem.Action("Jump to time...", onClick = vm::promptJumpToTime),
        AmpMenuItem.Action("Jump to file...", onClick = vm::promptJumpToFile),
        AmpMenuItem.Divider,
        AmpMenuItem.Action("Start of list") { vm.playTrack(0) },
        AmpMenuItem.Action("Ten tracks back") { vm.playTrack((vm.state.currentIndex - TEN_BACK).coerceAtLeast(0)) },
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
): AmpMenu =
    AmpMenu(
        "Options",
        optionsItems(vm, onPreferences, skinItems(vm, onPickSkin, onMuseum), onAlwaysOnTop),
        anchor,
    )

/** Winamp's Skins submenu: the browser, the picker, and every skin in the library. */
internal fun skinItems(
    vm: WinampViewModel,
    onPickSkin: () -> Unit,
    onMuseum: () -> Unit,
): List<AmpMenuItem> =
    buildList {
        add(
            AmpMenuItem.Action("Skin Browser...", needsTheApp = true) {
                vm.state.skinManagerOpen = true
                vm.state.raiseWindow("skins")
            },
        )
        // Winamp's browser is a folder of skins; on a phone a skin arrives
        // through the picker, so this is what "put one in the folder" means
        // not marked either: the picker belongs to whoever hosts the menu, and
        // both hosts can open one
        add(AmpMenuItem.Action("Load skin...", onClick = onPickSkin))
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
        add(AmpMenuItem.Action("<< Get more skins! >>", onClick = onMuseum))
        // the library survives restarts, so past skins stay one tap away
        vm.state.skinEntries.withIndex().filterNot { it.value.id == SkinEntry.BASE_ID }.forEach { (index, entry) ->
            add(
                AmpMenuItem.Action(entry.name, checked = entry.id == vm.skinOps.currentId) {
                    vm.skinOps.applyAt(index)
                },
            )
        }
    }

/** The Options list itself, shown under Options and behind the clutter bar's O. */
private fun optionsItems(
    vm: WinampViewModel,
    onPreferences: () -> Unit,
    skins: List<AmpMenuItem>,
    onAlwaysOnTop: () -> Unit,
): List<AmpMenuItem> =
    listOf(
        AmpMenuItem.Action("Preferences...", onClick = onPreferences),
        AmpMenuItem.Submenu("Skins", skins),
        AmpMenuItem.Divider,
        AmpMenuItem.Action("Time elapsed", checked = !vm.state.timeRemaining) { vm.state.timeRemaining = false },
        AmpMenuItem.Action("Time remaining", checked = vm.state.timeRemaining) { vm.state.timeRemaining = true },
        AmpMenuItem.Divider,
        // Winamp's own group, minus Double Size and EasyMove: a phone has one
        // size and every window here already moves with a finger
        AmpMenuItem.Action("Always On Top", checked = vm.state.alwaysOnTop, needsTheApp = true, onClick = onAlwaysOnTop),
        AmpMenuItem.Divider,
        AmpMenuItem.Action("Repeat", checked = vm.state.repeat) { vm.toggleRepeat() },
        AmpMenuItem.Action("Shuffle", checked = vm.state.shuffle) { vm.toggleShuffle() },
    )
