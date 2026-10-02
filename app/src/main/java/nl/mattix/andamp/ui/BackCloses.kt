// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import nl.mattix.andamp.state.WinampState

/**
 * What the back gesture closes before it leaves the app: the skin manager, then the library
 * (through [libraryBack]), then the fullscreen visualizer. An open menu consumes the gesture
 * where it is drawn.
 */
@Composable
fun BackCloses(
    state: WinampState,
    libraryBack: () -> Unit = { state.libraryOpen = false },
) {
    // innermost first; only what is open takes the gesture, so the last back leaves the app
    BackHandler(enabled = state.skinManagerOpen) { state.skinManagerOpen = false }
    // inside the library, back goes up a level before it closes the window
    BackHandler(enabled = !state.skinManagerOpen && state.libraryOpen) { libraryBack() }
    BackHandler(enabled = !state.skinManagerOpen && !state.libraryOpen && state.milkdropFullscreen) {
        state.milkdropFullscreen = false
    }
}
