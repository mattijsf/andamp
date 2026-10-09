// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

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
}
