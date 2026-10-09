// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.math.abs

/**
 * Where a window dragged through the locked layout goes: into the column under it, before
 * the first window whose middle is below it. The other windows are judged where they are
 * now, so once the dragged window has passed one, that one moves out of its way and the
 * two do not trade places again until the drag comes back past its new middle.
 */
object LockedReorder {
    /**
     * [columns] with [id] moved to the slot under [center]. [bands] are the columns' places
     * across the screen, and [rects] where the other windows are; a window without one is
     * not shown and keeps its place in the list.
     */
    fun moved(
        id: String,
        center: IntOffset,
        columns: List<List<String>>,
        bands: List<IntRect>,
        rects: Map<String, IntRect>,
    ): List<List<String>> {
        if (columns.none { id in it } || bands.size != columns.size) return columns
        val column =
            bands.indexOfFirst { center.x >= it.left && center.x < it.right }.takeIf { it >= 0 }
                ?: bands.indices.minByOrNull { abs(center.x - bands[it].center.x) }
                ?: return columns
        val without = columns.map { it.filterNot { other -> other == id } }
        val shown = without[column].filter { it in rects }
        val passed = shown.count { rects.getValue(it).center.y <= center.y }
        val target = without[column].toMutableList()
        val at = if (passed == 0) 0 else target.indexOf(shown[passed - 1]) + 1
        target.add(at, id)
        return without.mapIndexed { i, c -> if (i == column) target else c }
    }
}
