// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import nl.mattix.andamp.state.WinampState

/**
 * What the back gesture closes before it leaves the app: the skin manager, then the library
 * (through [libraryBack]), then the fullscreen visualizer, then a modern skin's window over its
 * main one (through [closeSkinWindow]). An open menu consumes the gesture where it is drawn.
 */
@Composable
fun BackCloses(
    state: WinampState,
    libraryBack: () -> Unit = { state.libraryOpen = false },
    /** Whether the modern skin being worn has a window open over its main one. */
    skinWindowOpen: Boolean = false,
    closeSkinWindow: () -> Unit = {},
) {
    // innermost first; only what is open takes the gesture, so the last back leaves the app
    BackHandler(enabled = state.skinManagerOpen) { state.skinManagerOpen = false }
    // inside the library, back goes up a level before it closes the window
    BackHandler(enabled = !state.skinManagerOpen && state.libraryOpen) { libraryBack() }
    BackHandler(enabled = !state.skinManagerOpen && !state.libraryOpen && state.milkdropFullscreen) {
        state.milkdropFullscreen = false
    }
    BackHandler(enabled = !state.skinManagerOpen && !state.libraryOpen && !state.milkdropFullscreen && skinWindowOpen) {
        closeSkinWindow()
    }
}
