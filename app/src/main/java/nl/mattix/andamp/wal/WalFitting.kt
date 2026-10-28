// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.modernskin.skin.Layout
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.StackArrange
import java.util.IdentityHashMap

/**
 * How the box a skin goes in on the screen reaches its runtime, and where its stack then sits. One
 * per engine, used on its thread only: it remembers which windows were on screen at the last fit,
 * how wide the stack was when nothing last slid, and how wide each main window was the first time
 * it was fitted.
 *
 * Sideways in the app (not the floating player, which is compact), a stack with a list window open
 * goes to two columns ([SkinRuntime.columns]) when that shows the skin larger than one column: the
 * main window and the windows that show no list on the left, the list windows on the right, the
 * last one as tall as the box. Both are weighed from the windows' natural sizes
 * ([StackArrange.naturalSize]), before any layout pass, so deciding sends the skin no events.
 *
 * The right column and the upright view go by how wide the main window is at rest
 * ([restingWidth]), not by what it shows now, so a drawer that is out of it (MMD3's equalizer) when
 * a window opens or the phone turns changes neither the columns nor the size the stack comes back
 * to.
 */
internal class WalFitting {
    /** The windows on screen at the last [apply] or [refit], compared by identity. */
    private var windows: List<Layout> = emptyList()

    /** What each main window showed across the first time it was fitted, by identity; see [restingWidth]. */
    private val firstWidths = IdentityHashMap<Layout, Int>()

    /** What the upright stack showed across while nothing slid, and the fit it was shown in; see [layout]. */
    private var restingW = 0
    private var restingFit: WalFit? = null

    /** The skin's color themes as [WalLayout.themes] carries them, worked out at the first [layout]; see [themesOf]. */
    private var themes: List<String>? = null

    /** The skin's options as [WalLayout.skinOptions] carries them, read again when they changed; see [optionsOf]. */
    private var options: SkinOptions? = null

    /** Gives [runtime] the box of [fit], as [WalEngine.fit] tells; [baseW] as [baseWOf] gives it. */
    fun apply(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
    ) {
        val layout = runtime.mainLayout ?: return
        windows = runtime.windows
        val box = IntSize(fit.widthPx, fit.heightPx)
        runtime.stretchLast = !fit.sideways && !fit.compact
        if (fillsBox(layout)) {
            // its scale from its minimum size, its size from what the box holds at that scale; in a
            // compact box no taller than its minimum
            val s = WalScale.of(box, layout.minW, layout.minH)
            val w = maxOf(layout.minW, (box.width / s).toInt())
            val h = if (fit.compact) layout.minH else maxOf(layout.minH, (box.height / s).toInt())
            if (layout.size != w to h) layout.resizeTo(w, h)
        }
        // sideways the whole stack sets the scale, and the windows docked under the main one are
        // sized only once the skin has a view
        if (fit.sideways) {
            decideColumns(runtime, fit)
        } else {
            runtime.columns = 1
            uprightView(runtime, box, baseW)
        }
    }

    /**
     * Decides the columns and the view again before a frame is drawn, when the windows on screen
     * are not the ones the last [apply] or [refit] saw, compared one by one by identity: one opened
     * or closed, or a window switched layout (a shade). Upright the stack stays in one column.
     */
    fun refit(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
    ) {
        val now = runtime.windows
        if (now.size == windows.size && now.indices.all { now[it] === windows[it] }) return
        windows = now
        if (fit.sideways) decideColumns(runtime, fit) else uprightView(runtime, IntSize(fit.widthPx, fit.heightPx), baseW)
    }

    /**
     * Gives [runtime] the view [box] holds upright: at the main window's scale ([WalScale.fit]), or
     * smaller when a docked window its minimum width makes wider than the main one would run past
     * the box at that scale (CornerAmp's playlist), so the last list window still reaches the
     * box's bottom at the scale the stack is drawn at ([WalScale.across]). The windows are measured
     * against the main window at rest ([restingWidth]), so a drawer that is out at the time leaves
     * the view as it is with the drawer closed.
     */
    private fun uprightView(
        runtime: SkinRuntime,
        box: IntSize,
        baseW: Int,
    ) {
        val main = runtime.mainLayout ?: return
        val shown = StackArrange.shownWidth(main, runtime.windows.drop(1), restingWidth(main))
        runtime.viewSize = view(box, WalScale.across(box.width, shown, WalScale.fit(box, main.size, baseW)))
    }

    /**
     * Sets [runtime] out sideways in [fit]'s box: in two columns when a list window is open, the
     * box is not compact, the main window does not fill the box ([sizedToBox]) and two columns show
     * the skin larger than one (more than [TIE] larger), else in one; and gives it the view at the
     * scale chosen, so the last list window of two columns takes the box's height. A stack in one
     * column keeps each window's height, as the skin gives it. The right column is as wide as the
     * main window at rest ([restingWidth], [SkinRuntime.columnWidth]).
     */
    private fun decideColumns(
        runtime: SkinRuntime,
        fit: WalFit,
    ) {
        val main = runtime.mainLayout ?: return
        val others = runtime.windows.drop(1)
        val box = IntSize(fit.widthPx, fit.heightPx)
        val width = restingWidth(main)
        runtime.columnWidth = width

        fun scale(columns: Int) =
            StackArrange
                .naturalSize(main, others, columns, runtime::showsList, width)
                .let { (w, h) -> WalScale.of(box, w, h) }
        val one = scale(1)
        val two = if (fit.compact || sizedToBox(main) || others.none(runtime::showsList)) null else scale(2)
        val wider = two != null && two > one * (1 + TIE)
        runtime.columns = if (wider) 2 else 1
        runtime.stretchLast = wider
        runtime.viewSize = view(box, if (wider) two else one)
    }

    /**
     * Where a stack of [size] sits in [fit]'s box, worked out again for each frame, since a window
     * opening changes the stack. The layout's stack is the part of it the windows show ([drawn],
     * see [SkinRuntime.drawnSize]), so the window rect, the touch area and the visualizer window
     * docked under the skin go by where the skin ends; in two columns the visualizer window docks
     * under the left one ([WalLayout.dock]). A touch 24 dp beside an object still means it at that
     * scale. The layout carries [wantsFrames] as the frame has it, the skin's color themes with
     * the one it wears, and the skin's options for Winamp's Options menu.
     *
     * Upright, a stack wider across than the box (MMD3's drawer slid out) is shrunk to fit once the
     * slide ends, and grows back once a slide closing it ends: while something slides, the width
     * the stack showed before it started holds the scale, so the scale does not change on every
     * frame of the slide.
     */
    @Suppress("LongParameterList") // the frame's parts the engine has worked out already
    fun layout(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
        size: Pair<Int, Int>,
        drawn: Pair<Int, Int>,
        windowOpen: Boolean,
        wantsFrames: Boolean,
    ): WalLayout {
        if (runtime.moving.isEmpty() || restingFit != fit) {
            restingW = drawn.first
            restingFit = fit
        }
        val place = place(runtime, fit, baseW, size, drawn)
        runtime.touchSlop = kotlin.math.ceil(TOUCH_SLOP_DP * fit.density / place.scale).toInt()
        val themes = themesOf(runtime)
        return WalLayout(
            fit = fit,
            scale = place.scale,
            left = place.left,
            top = place.top,
            stackW = drawn.first,
            stackH = drawn.second,
            windowOpen = windowOpen,
            animating = runtime.animating,
            menuTarget = runtime.menuTarget,
            menuChoices = runtime.skinMenuChoices,
            moveTarget = runtime.moveTarget,
            wantsFrames = wantsFrames,
            dock = dockOf(runtime, drawn),
            edgePlacements = runtime.edgePlacements,
            themes = themes,
            theme = runtime.currentGammaSet?.let { worn -> themes.firstOrNull { it.equals(worn, ignoreCase = true) } },
            skinOptions = optionsOf(runtime),
        )
    }

    /** [runtime]'s options for Winamp's Options menu, read again only when its Config items changed. */
    private fun optionsOf(runtime: SkinRuntime): SkinOptions =
        options?.takeIf { it.version == runtime.configVersion } ?: runtime.skinOptions().also { options = it }

    /**
     * [runtime]'s color themes in the order the skin's theme list shows them: sorted ignoring case,
     * one per name ignoring case (the first the skin defines, which is the one picking that name
     * puts on), without blank names. A skin reads all its themes as it loads, so they are worked
     * out once.
     */
    private fun themesOf(runtime: SkinRuntime): List<String> =
        themes ?: buildList<String> {
            for (name in runtime.gammaSetNames()) {
                if (name.isNotBlank() && none { it.equals(name, ignoreCase = true) }) add(name)
            }
        }.sortedWith(String.CASE_INSENSITIVE_ORDER).also { themes = it }

    /**
     * How wide [main] shows across at rest, in layout pixels: what it showed the first time it was
     * fitted (MMD3's main window with its drawers closed, as it starts; its shade bar at its full
     * width), so a drawer that is out later does not count. A window sized to the box both ways
     * (Bento) has no drawer to leave out and is measured as it is now, since a new box resizes it.
     */
    private fun restingWidth(main: Layout): Int {
        main.layoutRoot()
        if (main.resizesHorizontally() && main.resizesVertically()) return main.contentRight()
        return firstWidths.getOrPut(main) { main.contentRight() }
    }

    /**
     * Where a stack of [size], showing [drawn] of it, goes in [fit]'s box; see [WalScale.place]. It
     * goes against the edges of the box a script moved the main window against in the viewport, in
     * the layout it shows ([Layout.edgeX], [Layout.edgeY]), unless the box sizes that layout
     * ([fillsBox]), which then sits where the box puts it.
     */
    private fun place(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
        size: Pair<Int, Int>,
        drawn: Pair<Int, Int>,
    ): WalScale.Place {
        val layout = runtime.mainLayout
        val moved = layout?.takeUnless(::fillsBox)
        return WalScale.place(
            fit,
            layout?.size ?: size,
            baseW,
            layout?.contentRight() ?: size.first,
            size,
            drawn,
            restingW,
            moved?.edgeX,
            moved?.edgeY,
        )
    }

    /** The left column of [runtime]'s stack in two columns, within the [drawn] stack; null in one column. */
    private fun dockOf(
        runtime: SkinRuntime,
        drawn: Pair<Int, Int>,
    ): IntRect? {
        val main = runtime.mainLayout ?: return null
        val column =
            StackArrange.leftColumn(main, runtime.windows.drop(1), runtime.columns, runtime::showsList, runtime::windowRect)
                ?: return null
        return IntRect(column.left, column.top, minOf(column.right, drawn.first), minOf(column.bottom, drawn.second))
    }

    companion object {
        /** How far beside an object a finger may land and still mean it: every control then answers across Android's 48 dp. */
        private const val TOUCH_SLOP_DP = 24f

        /**
         * How much larger two columns must show the skin to be used, as a share of one column's
         * scale: two columns that gain less are a tie, and a tie stays in one column, so a window
         * opening does not set the whole stack out again for a skin hardly any larger.
         */
        private const val TIE = 0.02f

        /** The view [box] gives at [scale], in layout pixels. */
        private fun view(
            box: IntSize,
            scale: Float,
        ) = (box.width / scale).toInt() to (box.height / scale).toInt()

        /** Whether [apply] sizes [layout] to the box (Bento): it has a minimum size and resizes both ways. */
        private fun fillsBox(layout: Layout): Boolean =
            layout.minW > 0 && layout.minH > 0 && layout.resizesHorizontally() && layout.resizesVertically()

        /**
         * Whether [layout] is a main window that fills the box ([fillsBox]) and is not held at its
         * minimum size by a maximum (DeClassified's main window has handles, and a maximum as small
         * as its minimum): no room is left beside it for a second column.
         */
        private fun sizedToBox(layout: Layout): Boolean =
            fillsBox(layout) && layout.maxW !in 1..layout.minW && layout.maxH !in 1..layout.minH

        /**
         * The main window's visible width at the start, which sets the scale (MMD3 keeps room on its
         * right for a panel that slides out); fixed, so the size does not jump when that panel opens.
         * A window the user resizes both ways (Bento) is sized to the screen and scales from its size: 0.
         */
        fun baseWOf(runtime: SkinRuntime): Int =
            runtime.mainLayout?.takeUnless { it.resizesHorizontally() && it.resizesVertically() }?.contentRight() ?: 0

        /** Throws unless the screen can show [runtime]: it has a main window, and a stack a phone can draw. */
        fun requireShowable(
            runtime: SkinRuntime,
            box: IntSize?,
        ) {
            val layout = runtime.mainLayout ?: error("no main layout")
            WalScale.unfit(layout.size, runtime.stackSize, baseWOf(runtime), box)?.let { error(it) }
        }
    }
}
