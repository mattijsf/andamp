// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.state.MenuAnchor
import kotlin.math.roundToInt

/**
 * Maps a menu's window-virtual anchor to device-pixel bounds inside the screen container.
 *
 * A virtual pixel is [scale] pixels of the surface the windows are laid out on, and [shrink]
 * is how much smaller that surface is drawn on the screen.
 *
 * It uses the rectangle the window published in `WinampState.windowRects`, because the windows
 * float and can be dragged away from the docked layout. A window with no published rectangle has
 * not laid out yet; its menu lands mid-screen, as does a menu with no anchor.
 */
fun menuAnchorBounds(
    anchor: MenuAnchor?,
    scale: Int,
    containerWidth: Int,
    containerHeight: Int,
    /** Every visible window's rectangle, in virtual px; `WinampState.windowRects`. */
    windowRects: Map<String, IntRect>,
    shrink: Float = 1f,
): IntRect {
    val window = anchor?.let { windowRects[it.window] }
    if (anchor == null || window == null) {
        return IntRect(IntOffset(containerWidth / 2, containerHeight / 3), IntSize.Zero)
    }
    val pixel = scale * shrink
    return IntRect(
        offset = IntOffset(((window.left + anchor.x) * pixel).roundToInt(), ((window.top + anchor.y) * pixel).roundToInt()),
        size = IntSize((anchor.w * pixel).roundToInt(), (anchor.h * pixel).roundToInt()),
    )
}
