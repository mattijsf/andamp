// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

/**
 * The height of the docked stack that begins at [top]: [anchor] plus every member of [under]
 * that is shown and still attached, in stack order. The playlist begins below it.
 *
 * A member that has left is skipped and the walk carries on: with the equalizer dragged away,
 * a plug-in window still following the stack sits directly under the player.
 */
fun dockedStackHeight(
    top: Int,
    anchor: Int,
    under: List<StackMember>,
): Int {
    var edge = top + anchor
    under.forEach { member ->
        if (!member.shown) return@forEach
        val attached = !member.placed || member.top?.let { kotlin.math.abs(it - edge) <= SLACK } == true
        if (attached) edge += member.height
    }
    return edge - top
}

/**
 * One window as [dockedStackHeight] sees it.
 *
 * It is in the stack when it has no place of its own, or when its top is within [SLACK] px of
 * the run above it, which is what snapping a window back under the stack leaves.
 */
data class StackMember(
    /** Its current height, shaded or not. */
    val height: Int,
    /** Whether the window is open. */
    val shown: Boolean,
    /** Where its top edge is, or null while it has never been laid out. */
    val top: Int?,
    /** Whether it has a place of its own; false means it still follows the stack. */
    val placed: Boolean,
)

/** How many pixels apart two docked edges may read back; the same slack [DockGroup] allows. */
private const val SLACK = 2
