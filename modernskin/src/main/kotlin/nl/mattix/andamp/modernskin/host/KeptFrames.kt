// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.modernskin.host

import nl.mattix.andamp.modernskin.skin.LiveRun
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.WindowPlan

/**
 * What a host keeps of a modern skin's drawing between frames, and what it records again: a
 * drawing ([N], the host's: a render node, a picture) for each window and for each live run in it,
 * and one stack layer that draws the windows in order.
 *
 * Each frame the host hands it the frame's [WindowPlan]s with the windows' rects ([plan]) and gets
 * back, for each window, its drawing and whether to record it again, and the same for each run.
 * A window is recorded again when its plan's version changed, its size changed, a run came or
 * went, or it moved to or from the bottom of the stack ([Window.overClear]); a run when its
 * version changed. A window recorded as it was moves to its rect. The stack layer is recorded
 * again when the windows it draws, or its size, changed ([stackAgain]). Drawings of windows and
 * runs a frame no longer shows are let go of through [release].
 *
 * It assumes the host records what it is told to: a drawing that fails to record leaves it
 * wrong, so after a frame that threw the host calls [forget]. On the thread that draws.
 */
public class KeptFrames<N : Any>(
    /** A new, empty drawing. */
    private val create: () -> N,
    /** Lets go of a drawing no frame shows any more. */
    private val release: (N) -> Unit,
) {
    /** A run of a window as its plan gives it: its [drawing], recorded again when [again]. */
    public class Run<N>(
        public val run: LiveRun,
        public val drawing: N,
        public val again: Boolean,
    )

    /**
     * A window of the frame at [rect]: its [drawing], recorded again when [again] (else moved to
     * [rect]), and its [runs] in the plan's order. [overClear] for the bottom window, which draws
     * straight into the clear stack layer, so its shape masks the layer there.
     */
    public class Window<N>(
        public val plan: WindowPlan,
        public val rect: Rect,
        public val drawing: N,
        public val again: Boolean,
        public val overClear: Boolean,
        public val runs: List<Run<N>>,
    )

    private class KeptRun<N>(
        val drawing: N,
    ) {
        var version = Long.MIN_VALUE
        var frame = 0L
    }

    private class KeptWindow<N>(
        val drawing: N,
    ) {
        val runs = HashMap<Long, KeptRun<N>>()
        var version = Long.MIN_VALUE
        var width = -1
        var height = -1
        var overClear = false
        var frame = 0L
    }

    private val windows = HashMap<Long, KeptWindow<N>>()
    private var frame = 0L

    // the drawings the stack layer drew when last recorded, in order, at that whole scale and size
    private val order = ArrayList<N>()
    private val recorded = ArrayList<N>()
    private var recordedWhole = 0
    private var recordedSize = 0 to 0

    /** Windows kept. */
    public val keptWindows: Int get() = windows.size

    /** Runs kept, in all windows. */
    public val keptRuns: Int get() = windows.values.sumOf { it.runs.size }

    /**
     * The frame of [plans], one per window from the bottom up, with the windows at [rects]: what
     * to record again and what to keep. Windows and runs the frame does not show are let go of.
     */
    public fun plan(
        plans: List<WindowPlan>,
        rects: List<Rect>,
    ): List<Window<N>> {
        require(plans.size == rects.size) { "a rect for each plan" }
        frame++
        order.clear()
        val out = ArrayList<Window<N>>(plans.size)
        for (i in plans.indices) out.add(window(plans[i], rects[i], overClear = i == 0))
        windows.values.removeIf { w -> (w.frame != frame).also { if (it) drop(w) } }
        return out
    }

    private fun window(
        plan: WindowPlan,
        r: Rect,
        overClear: Boolean,
    ): Window<N> {
        val w = windows.getOrPut(plan.id) { KeptWindow(create()) }
        w.frame = frame
        order.add(w.drawing)
        var again = w.version != plan.version || w.width != r.width || w.height != r.height || w.overClear != overClear
        val runs =
            plan.runs.map { run ->
                val kept =
                    w.runs[run.id] ?: KeptRun(create()).also {
                        w.runs[run.id] = it
                        again = true
                    }
                kept.frame = frame
                val runAgain = kept.version != run.version
                kept.version = run.version
                Run(run, kept.drawing, runAgain)
            }
        if (w.runs.size != plan.runs.size) {
            w.runs.values.removeIf { kept -> (kept.frame != frame).also { if (it) release(kept.drawing) } }
            again = true
        }
        if (again) {
            w.version = plan.version
            w.width = r.width
            w.height = r.height
            w.overClear = overClear
        }
        return Window(plan, r, w.drawing, again, overClear, runs)
    }

    /**
     * Whether the stack layer, [width] x [height] at the [whole] scale it is recorded at, is to be
     * recorded again after the last [plan]: the windows it draws or its size changed. Once it says
     * so it counts the layer as recorded.
     */
    public fun stackAgain(
        whole: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val size = width to height
        if (order == recorded && whole == recordedWhole && size == recordedSize) return false
        recorded.clear()
        recorded.addAll(order)
        recordedWhole = whole
        recordedSize = size
        return true
    }

    /** Lets go of every window and run kept: the next frame records everything again. */
    public fun forget() {
        if (windows.isEmpty() && recorded.isEmpty()) return
        for (w in windows.values) drop(w)
        windows.clear()
        recorded.clear()
        recordedSize = 0 to 0
    }

    private fun drop(w: KeptWindow<N>) {
        release(w.drawing)
        for (kept in w.runs.values) release(kept.drawing)
        w.runs.clear()
    }

    public companion object {
        /**
         * Whether a frame of windows at [rects], each with its [plans] and [commands] draw commands,
         * can be kept this way: a plan for each window, every window with a width and a height, a
         * stack layer at [whole] no larger than [WalScale.MAX_PX] either way, and each plan's runs
         * in order, apart, inside its window's commands.
         */
        public fun fits(
            rects: List<Rect>,
            plans: List<WindowPlan>,
            commands: List<Int>,
            whole: Int,
        ): Boolean {
            if (rects.isEmpty() || plans.size != rects.size || commands.size != rects.size) return false
            if (rects.any { it.width <= 0 || it.height <= 0 }) return false
            val w = rects.maxOf { it.right }.toLong() * whole
            val h = rects.maxOf { it.bottom }.toLong() * whole
            if (w !in 1..WalScale.MAX_PX || h !in 1..WalScale.MAX_PX) return false
            return rects.indices.all { inOrder(plans[it].runs, commands[it]) }
        }

        /** Whether [runs] lie in order, apart, inside a list of [size] commands. */
        private fun inOrder(
            runs: List<LiveRun>,
            size: Int,
        ): Boolean {
            var at = 0
            for (run in runs) {
                if (run.from < at || run.to <= run.from || run.to > size) return false
                at = run.to
            }
            return true
        }
    }
}
