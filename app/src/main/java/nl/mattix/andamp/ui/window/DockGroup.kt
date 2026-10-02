// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntRect

/**
 * The windows that travel with the one being dragged: everything docked to it, directly or
 * through others. Docked means edges touching with an overlap along the other axis, which is
 * what a snap leaves.
 */
object DockGroup {
    /** [anchor] and everything flush against it, directly or through others. */
    fun of(
        anchor: String,
        rects: Map<String, IntRect>,
    ): Set<String> {
        val group = mutableSetOf(anchor)
        var grew = true
        while (grew) {
            grew = false
            rects.forEach { (id, rect) ->
                if (id in group) return@forEach
                if (group.any { member -> rects[member]?.let { touching(it, rect) } == true }) {
                    group += id
                    grew = true
                }
            }
        }
        return group
    }

    /**
     * The rectangle a whole group covers once its anchor has been moved to [anchorAt], so a
     * group can snap by its outer edges. The carried windows keep their places relative to
     * the anchor during a drag, so their current rectangles are enough.
     */
    fun bounds(
        anchorAt: IntRect,
        anchorNow: IntRect,
        carried: List<IntRect>,
    ): IntRect =
        carried.fold(anchorAt) { union, rect ->
            val moved =
                IntRect(
                    rect.left + (anchorAt.left - anchorNow.left),
                    rect.top + (anchorAt.top - anchorNow.top),
                    rect.right + (anchorAt.left - anchorNow.left),
                    rect.bottom + (anchorAt.top - anchorNow.top),
                )
            IntRect(
                minOf(union.left, moved.left),
                minOf(union.top, moved.top),
                maxOf(union.right, moved.right),
                maxOf(union.bottom, moved.bottom),
            )
        }

    /**
     * Edge against edge, with an overlap along the other axis.
     *
     * Edges up to [FLUSH_SLACK] px apart count: a window's place is a center-relative offset
     * that goes through integer division, so a stack snapped flush can read back a pixel
     * apart.
     */
    fun touching(
        a: IntRect,
        b: IntRect,
    ): Boolean {
        val verticallyFlush = near(a.bottom, b.top) || near(b.bottom, a.top)
        val horizontallyFlush = near(a.right, b.left) || near(b.right, a.left)
        val overlapX = a.left < b.right && b.left < a.right
        val overlapY = a.top < b.bottom && b.top < a.bottom
        return (verticallyFlush && overlapX) || (horizontallyFlush && overlapY)
    }

    /** Whether two edges are within [FLUSH_SLACK] px of each other. */
    private fun near(
        a: Int,
        b: Int,
    ) = kotlin.math.abs(a - b) <= FLUSH_SLACK

    private const val FLUSH_SLACK = 2
}
