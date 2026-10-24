// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.Layout
import nl.mattix.andamp.modernskin.skin.SkinRuntime

/**
 * How the box a skin goes in on the screen reaches its runtime, and where its stack then sits. One
 * per engine, used on its thread only: it remembers which windows were on screen at the last fit.
 */
internal class WalFitting {
    /** The windows on screen at the last [apply] or [refit], compared by identity. */
    private var windows: List<Layout> = emptyList()

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
        val hasMinimum = layout.minW > 0 && layout.minH > 0
        if (hasMinimum && layout.resizesHorizontally() && layout.resizesVertically()) {
            // its scale from its minimum size, its size from what the box holds at that scale; in a
            // compact box no taller than its minimum
            val s = WalScale.of(box, layout.minW, layout.minH)
            val w = maxOf(layout.minW, (box.width / s).toInt())
            val h = if (fit.compact) layout.minH else maxOf(layout.minH, (box.height / s).toInt())
            if (layout.size != w to h) {
                layout.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(w), Value.Int(h)))
            }
        }

        fun view(s: Float) = (box.width / s).toInt() to (box.height / s).toInt()
        runtime.viewSize = view(WalScale.fit(box, layout.size, baseW))
        // sideways the whole stack sets the scale, and the windows docked under the main one are
        // sized only once the skin has a view
        if (fit.sideways) runtime.viewSize = view(place(runtime, fit, baseW, runtime.stackSize).scale)
    }

    /**
     * Fits [runtime] to [fit] again, before a frame is drawn, when the windows on screen are not
     * the ones the last [apply] or [refit] saw, one by one: one opened or closed, or a window
     * switched layout (a shade). It notes the windows and leaves the runtime as [apply] set it: the
     * view and the single column stay as they are whichever windows are open.
     */
    @Suppress("UnusedParameter") // what [apply] takes, as the engine passes it before every frame
    fun refit(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
    ) {
        val now = runtime.windows
        if (now.size == windows.size && now.indices.all { now[it] === windows[it] }) return
        windows = now
    }

    /**
     * Where a stack of [size] sits in [fit]'s box, worked out again for each frame, since a window
     * opening changes the stack. The layout's stack is the part of it the windows show ([drawn],
     * see [SkinRuntime.drawnSize]), so the window rect, the touch area and the visualizer window
     * docked under the skin go by where the skin ends. A touch 24 dp beside an object still means
     * it at that scale. The layout carries [wantsFrames] as the frame has it.
     */
    fun layout(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
        size: Pair<Int, Int>,
        drawn: Pair<Int, Int>,
        windowOpen: Boolean,
        wantsFrames: Boolean,
    ): WalLayout {
        val place = place(runtime, fit, baseW, size, drawn)
        runtime.touchSlop = kotlin.math.ceil(TOUCH_SLOP_DP * fit.density / place.scale).toInt()
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
            moveTarget = runtime.moveTarget,
            wantsFrames = wantsFrames,
        )
    }

    /** Where a stack of [size], showing [drawn] of it, goes in [fit]'s box; see [WalScale.place]. */
    private fun place(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
        size: Pair<Int, Int>,
        drawn: Pair<Int, Int> = size,
    ): WalScale.Place {
        val layout = runtime.mainLayout
        return WalScale.place(fit, layout?.size ?: size, baseW, layout?.contentRight() ?: size.first, size, drawn)
    }

    companion object {
        /** How far beside an object a finger may land and still mean it: every control then answers across Android's 48 dp. */
        private const val TOUCH_SLOP_DP = 24f

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
