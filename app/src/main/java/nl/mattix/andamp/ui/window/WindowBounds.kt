// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset

/**
 * Keeps floating windows reachable.
 *
 * A window may hang off the left or right edge by up to half its width. Vertically its title
 * bar must stay between [clamp]'s `safeTop` and `safeBottom`: the system's gesture bar sits
 * inside the screen bounds, and a title bar under it cannot be grabbed.
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
        val maxX = ((screenW - windowW) / 2 + windowW / 2).coerceAtLeast(0)
        // top = (screenH - windowH) / 2 + y, and the title bar must satisfy
        // safeTop <= top and top + titleH <= safeBottom
        val centeredTop = (screenH - windowH) / 2
        val minY = safeTop - centeredTop
        val maxY = (safeBottom - titleH - centeredTop).coerceAtLeast(minY)
        return IntOffset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(minY, maxY))
    }
}
