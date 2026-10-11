// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.SIDEWAYS_STACK_H
import nl.mattix.andamp.ui.SIDEWAYS_STACK_W

/**
 * Where the floating windows sit until they are moved: Winamp's classic stack.
 *
 * Upright, one column in the middle of the screen: the player, with the equalizer and the
 * plug-in window under it, and the playlist under those, as tall as what is left. Sideways,
 * the player and the equalizer on the left and the playlist beside them, as tall as the two.
 *
 * A window that has been dragged away leaves no gap: the one under it moves up. A window
 * that was snapped back under the stack is in it again, which [Member.attachedAt] reads off
 * its top edge.
 */
object ClassicStack {
    /** A window under the player: its height, where its top is now, and whether it has a place of its own. */
    data class Member(
        val height: Int,
        /** Where its top edge is, or null while it has never been laid out. */
        val top: Int?,
        /** Whether it has a place of its own; false means it still follows the stack. */
        val placed: Boolean,
    ) {
        /** Whether it is in the stack: it follows it, or its top is where the stack would put it. */
        fun attachedAt(row: Int) = !placed || top?.let { kotlin.math.abs(it - row) <= DockGroup.FLUSH_SLACK } == true
    }

    data class Ask(
        val screen: WindowScreen,
        /** Whether the phone is held sideways; see [ClassicStack]. */
        val sideways: Boolean,
        /** The player's height, collapsed or not. */
        val mainH: Int,
        /** The equalizer, or null while it is closed. */
        val eq: Member?,
        /** The plug-in window, or null while it is closed. */
        val vis: Member?,
    )

    /** Each window's top-left corner until it is moved, in virtual px. */
    data class Places(
        val main: IntOffset,
        val eq: IntOffset,
        val vis: IntOffset,
        val playlist: IntOffset,
        /** How many segments tall the playlist is until it is resized: what fits where it sits. */
        val playlistSegments: Int,
    )

    fun layout(ask: Ask): Places {
        val screen = ask.screen
        val left = (screen.width - (if (ask.sideways) SIDEWAYS_STACK_W else MAIN_W)) / 2
        // the stack starts below the strip the system keeps for the notification shade
        var row = screen.safeTop
        val main = IntOffset(left, row)
        row += ask.mainH
        val eq = IntOffset(left, row)
        ask.eq?.takeIf { it.attachedAt(row) }?.let { row += it.height }
        val vis = IntOffset(left, row)
        ask.vis?.takeIf { it.attachedAt(row) }?.let { row += it.height }
        return if (ask.sideways) {
            Places(
                main,
                eq,
                vis,
                playlist = IntOffset(left + MAIN_W, screen.safeTop),
                PlaylistLayout.segmentsThatFit(SIDEWAYS_STACK_H),
            )
        } else {
            Places(main, eq, vis, playlist = IntOffset(left, row), PlaylistLayout.segmentsThatFit(screen.safeBottom - row))
        }
    }
}

/** [id] as the classic stack sees it, or null while it is closed. */
fun WinampState.classicMember(
    id: String,
    height: Int,
    open: Boolean,
): ClassicStack.Member? =
    if (open) ClassicStack.Member(height, windowRects[id]?.top, placed = placementOf(id).offset != null) else null
