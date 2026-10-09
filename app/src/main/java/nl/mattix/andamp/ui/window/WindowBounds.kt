// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset

/**
 * Keeps floating windows reachable.
 *
 * A window may hang off the left or right edge by up to half its width. Vertically its title
 * bar must stay between [clamp]'s `safeTop` and `safeBottom`: the system's gesture bar sits
 * inside the screen bounds, and a title bar under it cannot be grabbed.
 *
 * The rules are stated once, as the rows and columns a window's top-left corner may take, so
 * a window clamped on its own and one clamped as part of a group land on the same pixel.
 */
object WindowBounds {
    @Suppress("LongParameterList") // the safe area needs both edges
    fun clamp(
        offset: IntOffset,
        windowW: Int,
        windowH: Int,
        screenW: Int,
        screenH: Int,
        titleH: Int,
        safeTop: Int = 0,
        safeBottom: Int = screenH,
    ): IntOffset {
        // offsets are measured from the container's center
        val centeredLeft = (screenW - windowW) / 2
        val centeredTop = (screenH - windowH) / 2
        val lefts = leftRange(windowW, screenW)
        val tops = topRange(titleH, safeTop, safeBottom)
        return IntOffset(
            offset.x.coerceIn(lefts.first - centeredLeft, lefts.last - centeredLeft),
            offset.y.coerceIn(tops.first - centeredTop, tops.last - centeredTop),
        )
    }

    /** The columns a window [windowW] wide may have its left edge on. */
    fun leftRange(
        windowW: Int,
        screenW: Int,
    ): IntRange {
        val centeredLeft = (screenW - windowW) / 2
        val reach = (centeredLeft + windowW / 2).coerceAtLeast(0)
        return (centeredLeft - reach)..(centeredLeft + reach)
    }

    /** The rows a window with a handle [titleH] tall may have its top edge on. */
    fun topRange(
        titleH: Int,
        safeTop: Int,
        safeBottom: Int,
    ): IntRange = safeTop..(safeBottom - titleH).coerceAtLeast(safeTop)

    /**
     * How far a group asked to move by [asked] along one axis may go, given how far each
     * member may move: the nearest travel every one of [allowed] permits. When no travel
     * satisfies them all, the tightest lower bound wins, as [clamp] does for one window.
     */
    fun travel(
        asked: Int,
        allowed: List<IntRange>,
    ): Int {
        val least = allowed.maxOf { it.first }
        val most = allowed.minOf { it.last }
        return asked.coerceIn(least, maxOf(most, least))
    }
}

/** This range moved by [by]. */
internal fun IntRange.shiftedBy(by: Int): IntRange = (first + by)..(last + by)
