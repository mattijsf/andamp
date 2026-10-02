// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.overlay

import androidx.compose.ui.unit.IntRect

/**
 * The bounds of the floating player's touch window.
 *
 * A system overlay takes every touch that lands on it, and the public SDK cannot make only part of
 * a window touchable. So the touchable window is only as large as the union of the rectangles the
 * skin windows published, in device pixels, and everything outside it falls through to the app
 * behind.
 *
 * A drag that leaves the window keeps arriving: Android delivers a whole gesture to the window that
 * received its first touch.
 */
object OverlayBounds {
    /**
     * The union of [rects] - virtual pixels, as windows publish them - in device
     * pixels, or null when nothing is drawn.
     *
     * @param scale the integer virtual-to-device factor
     * @param originX where virtual x=0 sits on the screen, from centring the content
     * @param originY where virtual y=0 sits, below the status bar
     * @param pad device pixels of slack, so a shadow or a rounded edge is not clipped
     */
    fun windowFor(
        rects: Collection<IntRect>,
        scale: Int,
        originX: Int,
        originY: Int,
        pad: Int = 0,
    ): IntRect? {
        val drawn = rects.filter { it.width > 0 && it.height > 0 }
        if (drawn.isEmpty()) return null
        val left = drawn.minOf { it.left }
        val top = drawn.minOf { it.top }
        val right = drawn.maxOf { it.right }
        val bottom = drawn.maxOf { it.bottom }
        return IntRect(
            left = originX + left * scale - pad,
            top = originY + top * scale - pad,
            right = originX + right * scale + pad,
            bottom = originY + bottom * scale + pad,
        )
    }
}
