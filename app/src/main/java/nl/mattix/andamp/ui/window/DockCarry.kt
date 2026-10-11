// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.state.WinampState

/**
 * Moves the windows docked under [id] by [delta] px, so they stay flush when [id] changes
 * height.
 *
 * Only what hangs under the window moves ([DockGroup.below]), and only windows that have a
 * place of their own: one still following the stack gets its row from the layout.
 */
internal fun WinampState.carryDockedBelow(
    id: String,
    delta: Int,
) {
    if (delta == 0) return
    if (id !in windowRects) return // never laid out: nothing is glued to it yet
    (DockGroup.below(id, windowRects) - id).forEach { member ->
        val rect = windowRects[member] ?: return@forEach
        val offset = placementOf(member).offset ?: return@forEach
        // clamped before it is stored, so the stored offset and the published rectangle agree
        val handle = windowHandles[member] ?: TITLE_BAR_H
        placeWindow(member, WindowBounds.clamp(IntOffset(offset.x, offset.y + delta), rect.size, screen(), handle))
    }
}
