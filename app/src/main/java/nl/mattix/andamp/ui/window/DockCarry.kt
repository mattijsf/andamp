// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore

/**
 * Moves the windows docked under [id] by [delta] px, so they stay flush when [id] changes
 * height.
 *
 * Only what is under the window moves, and only windows that have a place of their own: one
 * still following the stack gets its row from the layout.
 */
internal fun WinampState.carryDockedBelow(
    id: String,
    delta: Int,
) {
    if (delta == 0) return
    if (id !in windowRects) return // never laid out: nothing is glued to it yet
    val group = DockGroup.of(id, windowRects)

    // The moving set grows downward from [id]: a window moves only when it hangs under one
    // that is moving. The dock group also reaches sideways, and a window under a
    // side-by-side neighbor stays with that neighbor.
    val moving = mutableSetOf(id)
    var grew = true
    while (grew) {
        grew = false
        group.forEach { member ->
            if (member in moving) return@forEach
            val rect = windowRects[member] ?: return@forEach
            if (moving.any { hangsUnder(windowRects[it], rect) }) {
                moving += member
                grew = true
            }
        }
    }

    (moving - id).forEach { member ->
        val rect = windowRects[member] ?: return@forEach
        val offset = offsetInState(member) ?: return@forEach
        // clamped before it is stored, so the stored offset and the published rectangle agree
        placeWindow(
            member,
            WindowBounds.clamp(
                IntOffset(offset.x, offset.y + delta),
                rect.width,
                rect.height,
                screenW,
                screenH,
                titleHeightOf(member),
                safeBottom = safeBottom.takeIf { it > 0 } ?: screenH,
            ),
        )
    }
}

/**
 * The height of the title band that has to stay inside the safe area for a window to be
 * grabbable: the PLEDIT bar for the playlist, [TITLE_BAR_H] for every other window.
 */
private fun titleHeightOf(id: String) = if (id == WindowStore.PLAYLIST) Dest.PL_TOP_H else TITLE_BAR_H

/** Whether [under] sits directly beneath [above], sharing some width with it. */
private fun hangsUnder(
    above: IntRect?,
    under: IntRect,
): Boolean {
    if (above == null) return false
    val flush = kotlin.math.abs(above.bottom - under.top) <= FLUSH_SLACK
    val overlaps = above.left < under.right && under.left < above.right
    return flush && overlaps
}

/** The place a window was put by hand; null while it still follows the stack. */
internal fun WinampState.offsetInState(id: String): IntOffset? =
    when (id) {
        WindowStore.MAIN -> mainOffset
        WindowStore.EQ -> eqOffset
        WindowStore.MILKDROP -> milkdropOffset
        WindowStore.PLAYLIST -> plOffset
        WindowStore.LIBRARY -> libraryOffset
        WindowStore.SKINS -> skinManagerOffset
        else -> null
    }

/** How many pixels apart two docked edges may read back; the same slack [DockGroup] allows. */
private const val FLUSH_SLACK = 2
