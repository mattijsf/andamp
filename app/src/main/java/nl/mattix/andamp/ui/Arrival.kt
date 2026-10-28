// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.navigation.NavController

/**
 * Goes to [route], named from outside the screen
 * ([nl.mattix.andamp.state.WinampState.arrivalDestination]).
 *
 * A screen already showing is not opened again: it takes the page it was asked for itself, and a
 * new copy in its place would show its list instead. The player, under every screen, is reached
 * by closing the screens over it.
 */
internal fun NavController.arriveAt(route: String) {
    when {
        route == Screen.PLAYER -> popBackStack(Screen.PLAYER, inclusive = false)
        currentDestination?.route != route -> navigate(route)
    }
}
