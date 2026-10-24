// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.window.WindowScreen
import nl.mattix.andamp.ui.window.WindowSnap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where a modern skin's stack sits on a player surface, from the box it is fitted into and the
 * engine's [WalLayout]: its rectangle in the surface's pixels, its rectangle as the classic windows
 * publish theirs, and the conversions a touch and a menu need. The listener can move the stack
 * inside the box from where the engine puts it; the companion's functions say where it may go.
 *
 * The surface's pixels are those the player's windows are laid out in, [scale] of them to a
 * virtual pixel of the classic windows.
 */
internal data class WalPlacement(
    /** The stack in the surface's pixels; it covers every pixel the stack shows. */
    val stack: IntRect,
    /**
     * The stack in virtual pixels, left and top rounded down and right and bottom up, as
     * `windowRects[MAIN]` holds it for the classic windows, the floating player's touch window and
     * the menus.
     */
    val window: IntRect,
    /** Surface pixels per skin pixel. */
    val skinScale: Float,
    /** Surface pixels per virtual pixel. */
    val scale: Int,
) {
    /** The skin pixel under [at], a point in surface pixels from the stack's top-left corner. */
    fun toSkin(at: Offset): IntOffset = IntOffset(floor(at.x / skinScale).toInt(), floor(at.y / skinScale).toInt())

    /** The virtual pixel nearest to [at], a point in surface pixels from the stack's top-left corner. */
    fun pointAt(at: Offset): IntOffset =
        IntOffset(((stack.left + at.x) / scale).roundToInt(), ((stack.top + at.y) / scale).roundToInt())

    /**
     * A menu anchor at [point], a virtual pixel, relative to [published] as `menuAnchorBounds`
     * reads an anchor. [published] is the rect `windowRects[MAIN]` holds, which waits for a sliding
     * stack to stop; while it holds none, the anchor is relative to [window].
     */
    fun anchorAt(
        point: IntOffset,
        published: IntRect?,
    ): MenuAnchor {
        val main = published ?: window
        return MenuAnchor(WindowStore.MAIN, point.x - main.left, point.y - main.top, 1, 1)
    }

    companion object {
        /**
         * The box a modern skin is fitted into on a surface [widthPx] wide: the band of [screen] a
         * window's handle may stay in, from its safe top to its safe bottom, at [scale].
         */
        fun box(
            widthPx: Int,
            screen: WindowScreen,
            scale: Int,
        ): IntRect = IntRect(0, screen.safeTop * scale, widthPx, screen.safeBottom * scale)

        /** [box] as the engine is told it; [compact] for the floating player (see [WalFit.compact]). */
        fun fit(
            box: IntRect,
            density: Float,
            sideways: Boolean,
            compact: Boolean = false,
        ): WalFit = WalFit(box.width, box.height, density, sideways, compact)

        /**
         * Where [layout], worked out for [box], puts the stack on a surface of [scale] pixels per
         * virtual pixel, moved [shift] surface pixels from there.
         */
        fun of(
            box: IntRect,
            layout: WalLayout,
            scale: Int,
            shift: IntOffset = IntOffset.Zero,
        ): WalPlacement {
            val left = box.left + layout.left.roundToInt() + shift.x
            val top = box.top + layout.top.roundToInt() + shift.y
            val stack =
                IntRect(
                    left,
                    top,
                    left + ceil(layout.stackW * layout.scale).toInt(),
                    top + ceil(layout.stackH * layout.scale).toInt(),
                )
            val window =
                IntRect(
                    Math.floorDiv(stack.left, scale),
                    Math.floorDiv(stack.top, scale),
                    -Math.floorDiv(-stack.right, scale),
                    -Math.floorDiv(-stack.bottom, scale),
                )
            return WalPlacement(stack, window, layout.scale, scale)
        }

        /**
         * Where a stack whose unmoved rect is [home] may have its top-left corner, asked for [at]:
         * wholly inside [box] on an axis it is smaller than the box on, and where [home] has it on
         * an axis it fills.
         */
        fun clamp(
            at: IntOffset,
            home: IntRect,
            box: IntRect,
        ): IntOffset {
            fun axis(
                at: Int,
                home: Int,
                start: Int,
                room: Int,
            ) = if (room > 0) at.coerceIn(start, start + room) else home
            return IntOffset(
                axis(at.x, home.left, box.left, box.width - home.width),
                axis(at.y, home.top, box.top, box.height - home.height),
            )
        }

        /**
         * Where a stack whose unmoved rect is [home] lands when asked to have its top-left corner at
         * [at]: on each axis, against an edge of [box] or back where [home] has it, when one of
         * those is less than [distance] pixels away; the nearest wins, and [home] a tie. Classic
         * windows snap the same way ([WindowSnap]).
         */
        fun snap(
            at: IntOffset,
            home: IntRect,
            box: IntRect,
            distance: Int,
        ): IntOffset {
            fun axis(
                at: Int,
                vararg to: Int,
            ) = to.filter { abs(it - at) < distance }.minByOrNull { abs(it - at) } ?: at
            return IntOffset(
                axis(at.x, home.left, box.left, box.right - home.width),
                axis(at.y, home.top, box.top, box.bottom - home.height),
            )
        }

        /**
         * How far a stack whose unmoved rect is [home] is drawn from there when it was moved
         * [move]: as far, but held wholly inside [box] (see [clamp]). A stack that grew, as when a
         * drawer slides out, stays inside the box this way and goes back to where it was moved
         * once it shrinks again; a box drawn a little smaller on another surface holds it the
         * same way.
         */
        fun held(
            move: IntOffset,
            home: IntRect,
            box: IntRect,
        ): IntOffset = clamp(home.topLeft + move, home, box) - home.topLeft

        /**
         * How tall the classic stack holds the main window while a modern skin is worn, so the
         * plug-in window docks against the skin's [stack] (`windowRects[MAIN]`, virtual px): under
         * it while [visH] fits between it and [screen]'s safe bottom, else over it while it fits
         * above, else as low as leaves it [visH] above the safe bottom. Null while the stack has no
         * rect.
         */
        fun dockedMainH(
            stack: IntRect?,
            screen: WindowScreen,
            visH: Int,
        ): Int? {
            stack ?: return null
            val visTop =
                when {
                    stack.bottom + visH <= screen.safeBottom -> stack.bottom
                    stack.top - visH >= screen.safeTop -> stack.top - visH
                    else -> screen.safeBottom - visH
                }
            return (visTop - screen.safeTop).coerceAtLeast(0)
        }
    }
}
