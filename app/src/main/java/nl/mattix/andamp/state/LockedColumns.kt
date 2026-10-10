// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect

/**
 * The columns a locked workspace holds its windows in: each a list of window ids, top to
 * bottom. Upright one column, sideways two. A listener reorders them by dragging a window
 * into place; see [WinampState.liftWindow].
 */
object LockedColumns {
    /** The windows a listener can move within the locked layout, in the order they start in. */
    val STACK = listOf(WindowStore.MAIN, WindowStore.EQ, WindowStore.MILKDROP, WindowStore.PLAYLIST)

    /** Where the windows start: one column upright; sideways, the playlist beside the rest. */
    fun defaultFor(sideways: Boolean): List<List<String>> =
        if (sideways) {
            listOf(listOf(WindowStore.MAIN, WindowStore.EQ, WindowStore.MILKDROP), listOf(WindowStore.PLAYLIST))
        } else {
            listOf(STACK)
        }

    /**
     * [columns] made whole: as many columns as the way round has, every window of [STACK]
     * in exactly one of them, and nothing else. A window that was missing goes to the end
     * of the first column, and a column there is no room for empties into the last one.
     */
    fun normalize(
        columns: List<List<String>>,
        sideways: Boolean,
    ): List<List<String>> {
        val across = if (sideways) 2 else 1
        val kept = columns.map { column -> column.filter { it in STACK } }
        val fitted =
            (0 until across).map { i ->
                if (i == across - 1) kept.drop(i).flatten() else kept.getOrElse(i) { emptyList() }
            }
        val seen = mutableSetOf<String>()
        val once = fitted.map { column -> column.filter { seen.add(it) } }
        val missing = STACK.filterNot { it in seen }
        return once.mapIndexed { i, column -> if (i == 0) column + missing else column }
    }

    /** The columns as one string: windows by comma, columns by bar. */
    fun encode(columns: List<List<String>>): String = columns.joinToString("|") { it.joinToString(",") }

    fun decode(text: String): List<List<String>> =
        text.split('|').map { column -> column.split(',').map { it.trim() }.filter { it.isNotEmpty() } }

    /**
     * [columns] with [id] moved to the slot under [center]: into [column], before the first
     * window whose middle is below it. [rects] are where the other windows are; a window
     * without one is not shown and keeps its place in the list. The others are judged where
     * they are now, so once the dragged window has passed one, that one moves out of its way
     * and the two do not trade places again until the drag comes back past its new middle.
     */
    fun moved(
        id: String,
        center: IntOffset,
        column: Int,
        columns: List<List<String>>,
        rects: Map<String, IntRect>,
    ): List<List<String>> {
        if (columns.none { id in it } || column !in columns.indices) return columns
        val without = columns.map { it.filterNot { other -> other == id } }
        val shown = without[column].filter { it in rects }
        val passed = shown.count { rects.getValue(it).center.y <= center.y }
        val target = without[column].toMutableList()
        val at = if (passed == 0) 0 else target.indexOf(shown[passed - 1]) + 1
        target.add(at, id)
        return without.mapIndexed { i, c -> if (i == column) target else c }
    }
}
