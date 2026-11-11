// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore

/**
 * The player's place while its skin loads: the main window's size where the main window docks,
 * a dark box. It is the player's rect while it shows (`windowRects[MAIN]`), so a menu opens
 * against it and the floating player's touch window follows it, and a long press opens the main
 * menu, as on the skin. It moves as the main window does, and takes the windows docked to it along.
 */
@Composable
internal fun MainPlaceholder(
    state: WinampState,
    scale: Int,
    /** Where the main window docks until it is moved. */
    docked: IntOffset,
    /** Winamp's right click on the player: the main menu. */
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingSkinWindow(
        id = WindowStore.MAIN,
        state = state,
        scale = scale,
        width = MAIN_W,
        height = MAIN_H,
        offset = state.mainOffset,
        docked = docked,
        onMove = { state.mainOffset = it },
        titleH = TITLE_BAR_H,
        widgets = emptyList(),
        dragsGroup = true,
        onLongPress = onLongPress,
        modifier = modifier,
    ) {
        drawRect(LOADING)
    }
}

/** The box shown in a skin's place while it loads: near black, as the skins mostly are. */
private val LOADING = Color(0xFF151515)
