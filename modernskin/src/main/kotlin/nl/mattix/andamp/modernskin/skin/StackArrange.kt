// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * How a phone sets out a skin's windows as one stack: where each window sits ([arrange]), how tall
 * a docked window is before any stretch ([naturalHeight]), how large the stack is with every window
 * at that height ([naturalSize]), and which window a point of the stack means ([windowAt]).
 * [SkinRuntime] keeps the windows and calls in here.
 *
 * The stack has one column or two. In one, every window docks under the main one. In two (a phone
 * held sideways), the main window and the windows that show no list stay in the left column, and
 * the windows that show a list ([SkinRuntime.showsList]) go to a right column beside it. The right
 * column starts at the main window's full width, so a drawer sliding out of the main window to the
 * right (MMD3) never runs under it, or further right where a window in the left column is wider
 * (CornerAmp's equalizer, at least 275 px under a 246 px player). Its windows are as wide as the
 * app says ([Ask.columnWidth]), so a drawer sliding does not change the stack's size. With no list
 * window open, two columns are one.
 */
object StackArrange {
    /** What [arrange] sets out. */
    internal class Ask(
        /** The main window, which sits at the stack's corner at the size it has. */
        val main: Layout,
        /** The windows opened over the main one, in the order they opened. */
        val others: List<Layout>,
        /** The space the app has for the stack, in layout pixels; null leaves every window at the size it has. */
        val view: Pair<Int, Int>?,
        /** Whether the last window of a column may take the height [view] leaves; see [SkinRuntime.stretchLast]. */
        val stretchLast: Boolean,
        /** How many columns the app asks for ([SkinRuntime.columns]): 2 sets out two, anything else one. */
        val columns: Int,
        /** Whether a window shows a list, which shows more rows when the window is taller. */
        val showsList: (Layout) -> Boolean,
        /**
         * How wide the windows of a right column are, in layout pixels ([SkinRuntime.columnWidth]);
         * 0 for as wide as what shows of the main window ([Layout.contentRight]).
         */
        val columnWidth: Int = 0,
    )

    /** A column of docked windows: they hang from [top], one under the other, at [x], [width] wide. */
    private class Column(
        val x: Int,
        val top: Int,
        val width: Int,
        val windows: List<Layout>,
    )

    /**
     * The columns [others] dock in beside or under [main]: one under what is visible of the main
     * window ([Layout.contentBottom]), so the first window in it follows a drawer, as wide as what
     * shows of the main window ([Layout.contentRight]); and with [columns] at 2 and a list window
     * among them, a second for the list windows, from the top, [columnWidth] wide (0 for as wide as
     * the first), starting where the main window and every window left in the first one end.
     * [Layout.layoutRoot] must have run on [main].
     */
    private fun columnsOf(
        main: Layout,
        others: List<Layout>,
        columns: Int,
        showsList: (Layout) -> Boolean,
        columnWidth: Int,
    ): List<Column> {
        val under = main.contentBottom()
        val width = main.contentRight()
        val (lists, rest) = others.partition(showsList)
        if (columns != 2 || lists.isEmpty()) return listOf(Column(0, under, width, others))
        val right = rest.maxOfOrNull { sizeAt(it, width).first } ?: 0
        return listOf(
            Column(0, under, width, rest),
            Column(maxOf(main.size.first, right), 0, columnWidth.takeIf { it > 0 } ?: width, lists),
        )
    }

    /**
     * Where each window of [ask] sits, in layout pixels: the main window at (0, 0) at its size, and
     * each other window in its column ([columnsOf]) in the order they opened. With a view each
     * docked window is as wide as its column (under the main window, what shows of it,
     * [Layout.contentRight]; MMD3 keeps room on its right for a panel) and as tall as
     * [naturalHeight] says; the last one of a column takes the height left when [Ask.stretchLast]
     * is on, room is left, it can be made taller and it shows a list. Without a view (the
     * reference tests) every window keeps the size it has, in one column.
     */
    internal fun arrange(ask: Ask): Map<Layout, Rect> {
        val placed = LinkedHashMap<Layout, Rect>()
        val main = ask.main
        val (mw, mh) = main.size
        placed[main] = Rect(0, 0, mw, mh)
        main.layoutRoot()
        val view = ask.view
        if (view == null) {
            // without a screen size (the reference tests) every window keeps the size it has
            var y = main.contentBottom()
            for (l in ask.others) {
                val (w, h) = l.size
                placed[l] = Rect(0, y, w, h)
                y += h
            }
            return placed
        }
        for (column in columnsOf(main, ask.others, ask.columns, ask.showsList, ask.columnWidth)) {
            var y = column.top
            column.windows.forEachIndexed { i, l ->
                l.w = column.width
                val natural = naturalHeight(l)
                val left = view.second - y
                // the height the skin gives it (or its minimum), unless it is the last of its
                // column, room is left, the window can be made taller, it shows a list and
                // stretchLast is on
                val stretches =
                    ask.stretchLast && i == column.windows.lastIndex && left > natural && l.resizesVertically() &&
                        ask.showsList(l)
                l.h = if (stretches) left else skinHeight(l)
                val (w, h) = l.size
                placed[l] = Rect(column.x, y, w, h)
                y += h
            }
        }
        return placed
    }

    /**
     * How large the stack of [main] and [others] is when set out in [columns] with a view and every
     * window at its natural height ([naturalHeight]), as [arrange] sets it out with stretchLast
     * off and a right column [columnWidth] wide ([Ask.columnWidth]), in layout pixels. It changes
     * no window's size and runs no layout pass, so the skin hears nothing: the app weighs one
     * column against two with it.
     */
    fun naturalSize(
        main: Layout,
        others: List<Layout>,
        columns: Int,
        showsList: (Layout) -> Boolean,
        columnWidth: Int = 0,
    ): Pair<Int, Int> {
        var (right, bottom) = main.size
        main.layoutRoot()
        for (column in columnsOf(main, others, columns, showsList, columnWidth)) {
            var y = column.top
            for (l in column.windows) {
                val (w, h) = sizeAt(l, column.width)
                right = maxOf(right, column.x + w)
                y += h
            }
            bottom = maxOf(bottom, y)
        }
        return right to bottom
    }

    /**
     * The left column of a stack in two columns, in layout pixels from the stack's top-left corner,
     * where [rectOf] says each window of [main] and [others] sits: from the left edge to where the
     * right column starts (the main window's full width, or a wider window in the left column),
     * and from the top to where the lowest window in it ends as Winamp docks to it
     * ([Layout.snappedSize], so Winamp Modern's closed drawer is left out). Null for a stack in one
     * column: [columns] is not 2, or no list window is open.
     */
    fun leftColumn(
        main: Layout,
        others: List<Layout>,
        columns: Int,
        showsList: (Layout) -> Boolean,
        rectOf: (Layout) -> Rect,
    ): Rect? {
        if (columns != 2 || others.none(showsList)) return null
        val column = listOf(main) + others.filterNot(showsList)
        val bottom = column.maxOf { rectOf(it).top + it.snappedSize().second }
        return Rect(0, 0, column.maxOf { rectOf(it).right }, bottom)
    }

    /**
     * How far right the windows of a stack in one column show when [arrange] docks [others] under
     * [main] with a view while the main window shows [width] across (0 for what it shows now,
     * [Layout.contentRight]): that width, or a docked window its minimum width makes wider, in
     * layout pixels. It changes no window's size and runs no layout pass.
     */
    fun shownWidth(
        main: Layout,
        others: List<Layout>,
        width: Int = 0,
    ): Int {
        main.layoutRoot()
        val across = width.takeIf { it > 0 } ?: main.contentRight()
        return maxOf(across, others.maxOfOrNull { sizeAt(it, across).first } ?: 0)
    }

    /** [window]'s size when [arrange] docks it [width] wide at the height the skin gives it; it is left as it was. */
    private fun sizeAt(
        window: Layout,
        width: Int,
    ): Pair<Int, Int> {
        val keptW = window.w
        val keptH = window.h
        window.w = width
        window.h = skinHeight(window)
        try {
            return window.size
        } finally {
            window.w = keptW
            window.h = keptH
        }
    }

    /**
     * How tall [arrange] makes [window] when it docks it with a view, unless it is the last list
     * window and takes the height left: the height the skin gives it (its `h`), else the one its
     * size falls back on, within its minimum and maximum ([Layout.size]), in layout pixels. It
     * leaves [window] the height it has, and sends it no events.
     */
    fun naturalHeight(window: Layout): Int {
        val kept = window.h
        window.h = skinHeight(window)
        try {
            return window.size.second
        } finally {
            window.h = kept
        }
    }

    /** The height the skin's XML gives [window], or null when it gives none. */
    private fun skinHeight(window: Layout): Int? = window.attrs["h"]?.toIntLenient()

    /**
     * The window of [windows] (top to bottom, the main one first) under [at], a point of the stack,
     * where [rectOf] says each sits: the one drawn last when several are, as a docked window covers
     * the main window's empty drawer space. Off every window, the nearest one within [slop] layout
     * pixels, measured both ways (a stack in two columns has room beside a window as well as under
     * it), or null.
     */
    internal fun windowAt(
        windows: List<Layout>,
        rectOf: (Layout) -> Rect,
        at: Pair<Int, Int>,
        slop: Int,
    ): Layout? {
        val (x, y) = at

        fun distance2(r: Rect): Long {
            val dx = maxOf(r.left - x, 0, x - (r.right - 1)).toLong()
            val dy = maxOf(r.top - y, 0, y - (r.bottom - 1)).toLong()
            return dx * dx + dy * dy
        }
        return windows.lastOrNull { at in rectOf(it) }
            ?: windows
                .minByOrNull { distance2(rectOf(it)) }
                ?.takeIf { distance2(rectOf(it)) <= slop.toLong() * slop }
    }
}
