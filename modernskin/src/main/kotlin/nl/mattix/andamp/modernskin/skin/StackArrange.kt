// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * How a phone sets out a skin's windows as one stack: where each window sits ([arrange]), and which
 * window a point of the stack means ([windowAt]). [SkinRuntime] keeps the windows and calls in here.
 */
internal object StackArrange {
    /** What [arrange] sets out. */
    class Ask(
        /** The main window, which sits at the stack's corner at the size it has. */
        val main: Layout,
        /** The windows opened over the main one, in the order they opened. */
        val others: List<Layout>,
        /** The space the app has for the stack, in layout pixels; null leaves every window at the size it has. */
        val view: Pair<Int, Int>?,
        /** Whether the last window may take the height [view] leaves; see [SkinRuntime.stretchLast]. */
        val stretchLast: Boolean,
        /** How many columns the app asks for ([SkinRuntime.columns]); [arrange] sets out one whatever it says. */
        val columns: Int,
        /** Whether a window shows a list, which shows more rows when the window is taller. */
        val showsList: (Layout) -> Boolean,
    )

    /**
     * Where each window of [ask] sits, in layout pixels: the main window at (0, 0) at its size, and
     * each other window under it in the order they opened, the first docked under what is visible
     * of the main one ([Layout.contentBottom]), so it follows a drawer. With a view each docked
     * window is as wide as what shows of the main one ([Layout.contentRight]; MMD3 keeps room on
     * its right for a panel) and as tall as the skin makes it, or its minimum; the last one takes
     * the height left when [Ask.stretchLast] is on, room is left, it can be made taller and it shows
     * a list. Without a view (the reference tests) every window keeps the size it has.
     */
    fun arrange(ask: Ask): Map<Layout, Rect> {
        val placed = LinkedHashMap<Layout, Rect>()
        val main = ask.main
        val (mw, mh) = main.size
        placed[main] = Rect(0, 0, mw, mh)
        // the next window docks under what is visible of the main one, so it follows the drawer
        main.layoutRoot()
        var y = main.contentBottom()
        val others = ask.others
        val view = ask.view
        others.forEachIndexed { i, l ->
            // without a screen size (the reference tests) every window keeps the size it has
            if (view != null) {
                // as wide as what shows of the main window (MMD3 keeps room on its right for a panel)
                l.w = main.contentRight()
                // the height the skin gives it (or its minimum), unless it is the last, room is
                // left, the window can be made taller, it shows a list and stretchLast is on
                l.h = l.attrs["h"]?.toIntLenient()
                val natural = l.size.second
                val left = view.second - y
                val stretches =
                    ask.stretchLast && i == others.lastIndex && left > natural && l.resizesVertically() && ask.showsList(l)
                if (stretches) l.h = left
            }
            val (w, h) = l.size
            placed[l] = Rect(0, y, w, h)
            y += h
        }
        return placed
    }

    /**
     * The window of [windows] (top to bottom, the main one first) under [at], a point of the stack,
     * where [rectOf] says each sits: the one drawn last when several are, as a docked window covers
     * the main window's empty drawer space. Off every window, the nearest by height within [slop]
     * layout pixels above or below it, or null.
     */
    fun windowAt(
        windows: List<Layout>,
        rectOf: (Layout) -> Rect,
        at: Pair<Int, Int>,
        slop: Int,
    ): Layout? {
        val y = at.second

        fun distance(r: Rect) = maxOf(r.top - y, 0, y - (r.bottom - 1))
        return windows.lastOrNull { at in rectOf(it) }
            ?: windows
                .minByOrNull { distance(rectOf(it)) }
                ?.takeIf { distance(rectOf(it)) <= slop }
    }
}
