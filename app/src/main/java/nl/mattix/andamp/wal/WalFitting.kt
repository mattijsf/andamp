// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.walkit.Presentation
import nl.mattix.walkit.Rect
import nl.mattix.walkit.SkinOptions
import nl.mattix.walkit.SkinRuntime
import nl.mattix.walkit.WindowId
import nl.mattix.walkit.WindowInfo

/**
 * How the box a skin goes in on the screen reaches its runtime, and where its stack then sits. One
 * per engine, used on its thread only: it remembers which windows were on screen at the last fit,
 * how wide the stack was when nothing last slid, and how wide each main window was the first time
 * it was fitted.
 *
 * Sideways in the app (not the floating player, which is compact), a stack with a list window open
 * goes to two columns ([Presentation.columns]) when that shows the skin larger than one column: the
 * main window and the windows that show no list on the left, the list windows on the right, the
 * last one as tall as the box. Both are weighed from the windows' natural sizes
 * ([SkinRuntime.naturalStackSize]), before any layout pass, so deciding sends the skin no events.
 *
 * The right column and the upright view go by how wide the main window is at rest
 * ([restingWidth]), not by what it shows now, so a drawer that is out of it (MMD3's equalizer) when
 * a window opens or the phone turns changes neither the columns nor the size the stack comes back
 * to.
 */
internal class WalFitting(
    /**
     * Whether the skin is a classic one, whose windows are set out apart, moving one by one as in
     * Winamp, unless the clutter bar's D has it fill the screen ([WalFilled]), whatever
     * [WalApart] says. The floating player still stacks them.
     */
    private val classic: Boolean = false,
    /**
     * Puts a skin's windows where the host kept them, once the windows are set out apart
     * ([WalApart]): called on the engine thread each time apart comes on.
     */
    private val placer: (SkinRuntime) -> Unit = {},
    /** How the listener asked a classic playlist to look ([WalListStyle]); read at each frame. */
    private val listStyle: () -> WalListStyle = { WalListStyle() },
) {
    /** The windows on screen at the last [apply] or [refit]. */
    private var windows: List<WindowId> = emptyList()

    /** Whether the runtime was last set out apart ([apart]), and at what scale. */
    private var apartApplied = false
    private var apartScale = 1f

    /** Where the stack filling the screen ([fills]) was last placed; null when it does not fill it. */
    private var filledPlace: WalScale.Place? = null

    /** What each main window showed across the first time it was fitted; see [restingWidth]. */
    private val firstWidths = HashMap<WindowId, Int>()

    /** What the upright stack showed across while nothing slid, and the fit it was shown in; see [layout]. */
    private var restingW = 0
    private var restingFit: WalFit? = null

    /** The skin's color themes as [WalLayout.themes] carries them, worked out at the first [layout]; see [themesOf]. */
    private var themes: List<String>? = null

    /** The skin's options as [WalLayout.skinOptions] carries them, read again when they changed; see [optionsOf]. */
    private var options: SkinOptions? = null

    /** Gives [runtime] the box of [fit], as the host has it; [baseW] as [baseWOf] gives it. */
    fun apply(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
    ) {
        val main = runtime.mainWindow ?: return
        val layout = runtime.windowInfo(main)
        windows = runtime.windowIds
        val box = (fit.widthPx to fit.heightPx)
        if (apart(fit)) {
            setApart(runtime, fit, baseW)
            return
        }
        if (apartApplied) {
            apartApplied = false
            runtime.present { it.copy(freeWindows = false) }
        }
        if (fills(fit)) {
            setFilled(runtime, fit, baseW)
            return
        }
        filledPlace = null
        runtime.present { it.copy(stretchLast = !fit.sideways && !fit.compact) }
        if (fillsBox(layout)) {
            // its scale from its minimum size, its size from what the box holds at that scale; in a
            // compact box no taller than its minimum
            val s = WalScale.of(box, layout.minW, layout.minH)
            val w = maxOf(layout.minW, (box.first / s).toInt())
            val h = if (fit.compact) layout.minH else maxOf(layout.minH, (box.second / s).toInt())
            if (layout.size != w to h) runtime.resizeWindow(main, w, h)
        }
        // sideways the whole stack sets the scale, and the windows docked under the main one are
        // sized only once the skin has a view
        if (fit.sideways) {
            decideColumns(runtime, fit)
        } else {
            runtime.present { it.copy(columns = 1) }
            uprightView(runtime, box, baseW)
        }
    }

    /**
     * Decides the columns and the view again before a frame is drawn, when the windows on screen
     * are not the ones the last [apply] or [refit] saw: one opened or closed, or a window switched
     * layout (a shade). Upright the stack stays in one column.
     */
    fun refit(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
    ) {
        val now = runtime.windowIds
        if (switched(fit)) {
            apply(runtime, fit, baseW)
            return
        }
        if (now == windows || apartApplied) return
        windows = now
        if (filledPlace != null) {
            setFilled(runtime, fit, baseW)
        } else if (fit.sideways) {
            decideColumns(runtime, fit)
        } else {
            uprightView(runtime, (fit.widthPx to fit.heightPx), baseW)
        }
    }

    /**
     * Whether a switch between apart and filling the screen ([WalApart], [WalFilled]) waits for the
     * next [refit]: the runtime is still set out as before it.
     */
    fun switched(fit: WalFit): Boolean = apart(fit) != apartApplied || fills(fit) != (filledPlace != null)

    /**
     * Whether [fit] shows the windows apart: a [classic] skin's unless it fills the screen, else as
     * the switch says; never in the floating player's box.
     */
    private fun apart(fit: WalFit): Boolean = (if (classic) !WalFilled.filled else WalApart.apart) && !fit.compact

    /**
     * Whether [fit] shows a [classic] skin's stack filling the screen ([WalFilled]): upright in the
     * app, not apart; sideways the stack fits the box as any skin's does.
     */
    private fun fills(fit: WalFit): Boolean = classic && !apart(fit) && !fit.compact && !fit.sideways

    /**
     * Sets [runtime]'s stack to fill [fit]'s box: the main window as wide as the box, at a scale
     * that need not be whole, and the last window stretched to the box's bottom. A stack whose
     * windows would not fit the box's height at that scale is shrunk until they do, and centered
     * across.
     */
    private fun setFilled(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
    ) {
        val main = runtime.mainWindow ?: return
        windows = runtime.windowIds
        val across = baseW.takeIf { it > 0 } ?: runtime.windowSize(main).first
        val tall = runtime.naturalStackSize(1, across)?.second ?: 0
        val wide = fit.widthPx.toFloat() / across
        val scale = if (tall > 0) minOf(wide, fit.heightPx.toFloat() / tall) else wide
        val left = WalScale.left(fit.widthPx.toFloat(), across, scale)
        filledPlace = WalScale.Place(scale, left, 0f)
        runtime.present {
            it.copy(columns = 1, stretchLast = true, viewSize = across to (fit.heightPx / scale).toInt())
        }
    }

    /**
     * Sets [runtime]'s windows out apart in [fit]'s box: the box is the viewport, in skin px at
     * the main window's scale ([WalScale.fit], with [baseW]), and the windows go where the host
     * kept them ([placer]) the first time apart comes on.
     */
    private fun setApart(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
    ) {
        val main = runtime.mainWindow ?: return
        val box = (fit.widthPx to fit.heightPx)
        val scale = WalScale.fit(box, runtime.windowSize(main), baseW)
        apartScale = scale
        filledPlace = null
        val first = !apartApplied
        apartApplied = true
        runtime.present {
            it.copy(columns = 1, stretchLast = false, viewSize = view(box, scale), freeWindows = true)
        }
        if (first) placer(runtime)
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
        box: Pair<Int, Int>,
        baseW: Int,
    ) {
        val main = runtime.mainWindow ?: return
        val shown = runtime.shownWidth(restingWidth(runtime, main))
        runtime.present {
            it.copy(viewSize = view(box, WalScale.across(box.first, shown, WalScale.fit(box, runtime.windowSize(main), baseW))))
        }
    }

    /**
     * Sets [runtime] out sideways in [fit]'s box: in two columns when a list window is open, the
     * box is not compact, the main window does not fill the box ([sizedToBox]) and two columns show
     * the skin larger than one (more than [TIE] larger), else in one; and gives it the view at the
     * scale chosen, so the last list window of two columns takes the box's height. A stack in one
     * column keeps each window's height, as the skin gives it. The right column is as wide as the
     * main window at rest ([restingWidth], [Presentation.columnWidth]).
     */
    private fun decideColumns(
        runtime: SkinRuntime,
        fit: WalFit,
    ) {
        val main = runtime.mainWindow ?: return
        val box = (fit.widthPx to fit.heightPx)
        val width = restingWidth(runtime, main)
        runtime.present { it.copy(columnWidth = width) }

        fun scale(columns: Int) =
            checkNotNull(runtime.naturalStackSize(columns, width))
                .let { (w, h) -> WalScale.of(box, w, h) }
        val one = scale(1)
        val two = if (fit.compact || sizedToBox(runtime.windowInfo(main)) || !runtime.listWindowOpen) null else scale(2)
        val wider = two != null && two > one * (1 + TIE)
        runtime.present {
            it.copy(
                columns = if (wider) 2 else 1,
                stretchLast = wider,
                viewSize = view(box, if (wider) two else one),
            )
        }
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
        if (!runtime.sliding || restingFit != fit) {
            restingW = drawn.first
            restingFit = fit
        }
        // apart, the whole box is the skin's viewport, at the main window's scale; filling the
        // screen, the view is the stack, as wide as the box
        val filled = filledPlace
        val view = runtime.presentation.viewSize?.takeIf { apartApplied || filled != null }
        val place =
            when {
                view == null -> place(runtime, fit, baseW, size, drawn)
                filled != null -> filled
                else -> WalScale.Place(apartScale, 0f, 0f)
            }
        val slop = kotlin.math.ceil(TOUCH_SLOP_DP * fit.density / place.scale).toInt()
        // a finger drives the skin: a tap on a classic playlist button leaves its menu open
        val lists = listStyle()
        runtime.present {
            it.copy(touchSlop = slop, touch = true, skinListFont = lists.skinFont, listRowExtra = lists.rowExtra)
        }
        val themes = themesOf(runtime)
        return WalLayout(
            fit = fit,
            scale = place.scale,
            left = place.left,
            top = place.top,
            stackW = view?.first ?: drawn.first,
            stackH = view?.second ?: drawn.second,
            windowOpen = windowOpen,
            animating = runtime.animating,
            sliding = runtime.sliding,
            menuTarget = runtime.menuTarget,
            moveTarget = runtime.moveTarget.takeIf { filled == null },
            filled = filled != null,
            wantsFrames = wantsFrames,
            dock = if (view != null) null else dockOf(runtime, drawn),
            edgePlacements = runtime.edgePlacements,
            themes = themes,
            theme = runtime.colorTheme?.let { worn -> themes.firstOrNull { it.equals(worn, ignoreCase = true) } },
            skinOptions = optionsOf(runtime),
            visRect = runtime.visRect,
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
            for (name in runtime.colorThemes) {
                if (name.isNotBlank() && none { it.equals(name, ignoreCase = true) }) add(name)
            }
        }.sortedWith(String.CASE_INSENSITIVE_ORDER).also { themes = it }

    /**
     * How wide [main] shows across at rest, in layout pixels: what it showed the first time it was
     * fitted (MMD3's main window with its drawers closed, as it starts; its shade bar at its full
     * width), so a drawer that is out later does not count. A window sized to the box both ways
     * (Bento) has no drawer to leave out and is measured as it is now, since a new box resizes it.
     */
    private fun restingWidth(
        runtime: SkinRuntime,
        main: WindowId,
    ): Int {
        val laidOut = runtime.contentRight(main, layOutFirst = true)
        if (runtime.windowInfo(main).resizableBothWays) return laidOut
        return firstWidths.getOrPut(main) { laidOut }
    }

    /**
     * Where a stack of [size], showing [drawn] of it, goes in [fit]'s box; see [WalScale.place]. It
     * goes against the edges of the box a script moved the main window against in the viewport, in
     * the layout it shows ([WindowInfo.edgeX], [WindowInfo.edgeY]), unless the box sizes that layout
     * ([fillsBox]), which then sits where the box puts it.
     */
    private fun place(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
        size: Pair<Int, Int>,
        drawn: Pair<Int, Int>,
    ): WalScale.Place {
        val main = runtime.mainWindow
        val layout = main?.let(runtime::windowInfo)
        val moved = layout?.takeUnless(::fillsBox)
        return WalScale.place(
            fit,
            layout?.size ?: size,
            baseW,
            main?.let { restingWidth(runtime, it) } ?: size.first,
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
    ): Rect? {
        val column = runtime.leftColumn() ?: return null
        return Rect(
            column.left,
            column.top,
            minOf(column.right, drawn.first) - column.left,
            minOf(column.bottom, drawn.second) - column.top,
        )
    }

    companion object {
        /**
         * How far beside an object a finger may land and still mean it: every control then answers
         * across a 48 dp touch target.
         */
        internal const val TOUCH_SLOP_DP = 24f

        /**
         * How much larger two columns must show the skin to be used, as a share of one column's
         * scale: two columns that gain less are a tie, and a tie stays in one column, so a window
         * opening does not set the whole stack out again for a skin hardly any larger.
         */
        private const val TIE = 0.02f

        /** Changes what [change] changes of [this]'s [Presentation]. */
        private inline fun SkinRuntime.present(change: (Presentation) -> Presentation) {
            presentation = change(presentation)
        }

        /** The view [box] gives at [scale], in layout pixels. */
        private fun view(
            box: Pair<Int, Int>,
            scale: Float,
        ) = (box.first / scale).toInt() to (box.second / scale).toInt()

        /** Whether [apply] sizes [layout] to the box (Bento): it has a minimum size and resizes both ways. */
        private fun fillsBox(layout: WindowInfo): Boolean = layout.minW > 0 && layout.minH > 0 && layout.resizableBothWays

        /**
         * Whether [layout] is a main window that fills the box ([fillsBox]) and is not held at its
         * minimum size by a maximum (DeClassified's main window has handles, and a maximum as small
         * as its minimum): no room is left beside it for a second column.
         */
        private fun sizedToBox(layout: WindowInfo): Boolean =
            fillsBox(layout) && layout.maxW !in 1..layout.minW && layout.maxH !in 1..layout.minH

        /**
         * The main window's visible width at the start, which sets the scale (MMD3 keeps room on its
         * right for a panel that slides out); fixed, so the size does not jump when that panel opens.
         * A window the user resizes both ways (Bento) is sized to the screen and scales from its size: 0.
         */
        fun baseWOf(runtime: SkinRuntime): Int =
            runtime.mainWindow?.takeUnless { runtime.windowInfo(it).resizableBothWays }?.let { runtime.contentRight(it) } ?: 0

        /** Throws unless the screen can show [runtime]: it has a main window, and a stack a phone can draw. */
        fun requireShowable(
            runtime: SkinRuntime,
            box: Pair<Int, Int>?,
        ) {
            val main = runtime.mainWindow ?: error("no main layout")
            WalScale.unfit(runtime.windowSize(main), runtime.stackSize, baseWOf(runtime), box)?.let { error(it) }
        }
    }
}
