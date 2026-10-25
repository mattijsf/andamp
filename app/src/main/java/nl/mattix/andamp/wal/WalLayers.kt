// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.graphics.RenderNode
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.LiveRun
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.WindowPlan
import kotlin.math.ceil

/**
 * Draws a frame as [drawWalStack] does, but keeps what it drew between frames, by the engine's
 * plans ([WindowPlan], parallel to [stack]): each window and each of its live runs is recorded
 * once and drawn as it is until its version moves. The whole stack is drawn into [layers]' stack
 * layer, the one picture off screen, which the screen draws again only where a recorded part
 * changed. A frame [layers] cannot draw this way is drawn by [drawWalStack], into the same stack
 * layer.
 */
internal fun DrawScope.drawWalStackLayered(
    stack: List<Pair<Rect, List<DrawCmd>>>,
    plans: List<WindowPlan>?,
    host: WalHost,
    scale: Float,
    layers: WalLayers,
    themeKey: String? = null,
) {
    val whole = ceil(scale).toInt().coerceAtLeast(1)
    val fractional = whole.toFloat() != scale && stack.isNotEmpty()
    if (plans == null || !layers.suits(stack, plans, whole, fractional)) {
        layers.forget()
        drawWalStack(stack, host, scale, 0f, layers.stackLayer, themeKey)
        return
    }
    host.drawing.begin(themeKey)
    try {
        layers.draw(this, stack, plans, host, if (fractional) scale / whole else 1f, whole, fractional)
    } finally {
        host.drawing.end()
    }
}

/**
 * The drawings one canvas keeps of a modern skin between frames ([drawWalStackLayered]): a stack
 * layer, off screen, holding a node for each window, which holds a node for each live run.
 *
 * The stack layer is as large as the stack at the whole scale at or above the one on screen, and
 * shrinks to fit at a fractional scale, as [drawWalStack] draws it; at a whole scale it is drawn as
 * it is, which gives the same pixels only where nothing is drawn under the stack ([clearBelow]),
 * and only while [WalRender.wholeOffscreen] is on.
 *
 * Its content is recorded again only when the windows drawn change; a window's node when anything
 * of it outside its runs changed; a run's node when the run changed. Window and run nodes clip to
 * their bounds and keep no picture apart, so the screen draws the stack layer again only
 * inside the bounds of what was recorded again. With [WalRender.partial] off the stack layer's
 * content is recorded again every frame, so the screen draws all of it again. Window and run nodes
 * are the platform's render nodes from Android 10 on ([nodes]); before that every frame is drawn
 * by [drawWalStack].
 *
 * What the kept drawings show is held in the skin's drawing ([WalDrawing.retain]) for as long as
 * they show it. On the main thread.
 */
internal class WalLayers(
    private val makeLayer: () -> GraphicsLayer,
    private val releaseLayer: (GraphicsLayer) -> Unit,
    /** Whether nothing is drawn under the stack, so a whole scale may be drawn off screen too. */
    private val clearBelow: Boolean,
    /** Whether window and run nodes are render nodes; else each draws its commands where it is drawn. */
    private val nodes: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    /** Whether frames are kept at all; without render nodes only a canvas that draws everything each time can. */
    private val keeps: Boolean = nodes,
) {
    private var stack: GraphicsLayer? = null

    /** The layer the stack is drawn into, also by [drawWalStack] for a frame drawn the plain way. */
    val stackLayer: GraphicsLayer get() = stack ?: makeLayer().also { stack = it }

    private class WindowNode(
        val node: WalNode,
    ) {
        val runs = HashMap<Long, WalNode>()
        var frame = 0L
        var overClear = false
    }

    private val windows = HashMap<Long, WindowNode>()

    // the windows drawn this frame, and those the stack layer's content draws, in order
    private val order = ArrayList<WalNode>()
    private val recorded = ArrayList<WalNode>()
    private var recordedWhole = 0
    private var recordedSize = IntSize.Zero
    private var frame = 0L

    // what this frame recorded again, for the timing log: windows, runs and the runs' area in skin px
    private var windowsAgain = 0
    private var runsAgain = 0
    private var runAreaAgain = 0L

    /** Windows and runs kept; for tests. */
    internal val keptWindows: Int get() = windows.size
    internal val keptRuns: Int get() = windows.values.sumOf { it.runs.size }

    /** Told of each recording, by kind ([STACK], [WINDOW], [RUN]) and id; for tests. */
    internal var onRecord: ((kind: String, id: Long) -> Unit)? = null

    /**
     * Whether a frame of [stack] with its [plans] can be drawn with kept drawings: the switches
     * allow it, the plans match the windows, every window has a width and a height, the stack
     * layer would not be too large, and a whole scale has nothing drawn under it.
     */
    fun suits(
        stack: List<Pair<Rect, List<DrawCmd>>>,
        plans: List<WindowPlan>,
        whole: Int,
        fractional: Boolean,
    ): Boolean {
        val switched = keeps && WalRender.layers && !WalRender.direct && (fractional || WalRender.wholeOffscreen)
        if (!switched || stack.isEmpty() || plans.size != stack.size) return false
        if (stack.any { (r, _) -> r.width <= 0 || r.height <= 0 }) return false
        val w = stack.maxOf { it.first.right }.toLong() * whole
        val h = stack.maxOf { it.first.bottom }.toLong() * whole
        val fits = w in 1..WalScale.MAX_PX && h in 1..WalScale.MAX_PX
        return fits && (fractional || clearBelow) && stack.indices.all { inOrder(plans[it].runs, stack[it].second.size) }
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

    /**
     * Draws a frame that [suits]: what changed recorded again, the rest kept, the stack layer drawn
     * at [layerScale] (the scale on screen over [whole], or 1).
     */
    @Suppress("LongParameterList") // the frame, its plans and how it is drawn
    fun draw(
        scope: DrawScope,
        stack: List<Pair<Rect, List<DrawCmd>>>,
        plans: List<WindowPlan>,
        host: WalHost,
        layerScale: Float,
        whole: Int,
        fractional: Boolean,
    ) {
        frame++
        order.clear()
        windowsAgain = 0
        runsAgain = 0
        runAreaAgain = 0L
        for (i in stack.indices) {
            val (r, cmds) = stack[i]
            order.add(window(scope, plans[i], r, cmds, host, overClear = fractional && i == 0))
        }
        windows.values.removeIf { w -> (w.frame != frame).also { if (it) release(w) } }
        val size = IntSize(stack.maxOf { it.first.right } * whole, stack.maxOf { it.first.bottom } * whole)
        val layer = stackLayer
        layer.compositingStrategy = CompositingStrategy.Offscreen
        layer.pivotOffset = Offset.Zero
        layer.scaleX = layerScale
        layer.scaleY = layerScale
        val placed = order == recorded && whole == recordedWhole && size == recordedSize
        // with partial drawing off, the screen draws the whole stack layer again every frame
        if (!placed || !WalRender.partial) {
            val drawn = order.toList()
            layer.record(scope, scope.layoutDirection, size) {
                scale(whole.toFloat(), whole.toFloat(), pivot = Offset.Zero) { for (n in drawn) n.draw(this) }
            }
            recorded.clear()
            recorded.addAll(order)
            recordedWhole = whole
            recordedSize = size
            onRecord?.invoke(STACK, 0L)
            if (WalTiming.on) Log.d(WalTiming.TAG, "layers: stack recorded again, ${order.size} windows")
        }
        scope.drawLayer(layer)
        if (WalTiming.on) {
            Log.d(
                WalTiming.TAG,
                "layers: again $windowsAgain of ${order.size} windows, $runsAgain of $keptRuns runs ($runAreaAgain px)",
            )
        }
    }

    /** The node of the window [plan] names, with what changed in it recorded again. */
    @Suppress("LongParameterList") // the window, its plan and how it is drawn
    private fun window(
        scope: DrawScope,
        plan: WindowPlan,
        r: Rect,
        cmds: List<DrawCmd>,
        host: WalHost,
        overClear: Boolean,
    ): WalNode {
        val w = windows.getOrPut(plan.id) { WindowNode(WalNode(nodes)) }
        w.frame = frame
        val sized = w.node.width != r.width || w.node.height != r.height
        var again = w.node.version != plan.version || sized || w.overClear != overClear
        for (run in plan.runs) {
            val node =
                w.runs[run.id] ?: WalNode(nodes).also {
                    w.runs[run.id] = it
                    again = true
                }
            node.frame = frame
            if (node.version != run.version) recordRun(scope, node, run, cmds, host) else host.drawing.retain(node.kept)
        }
        if (w.runs.size != plan.runs.size) {
            w.runs.values.removeIf { node -> (node.frame != frame).also { if (it) node.release() } }
            again = true
        }
        if (again) {
            recordWindow(scope, w, plan, r, cmds, host, overClear)
        } else {
            host.drawing.retain(w.node.kept)
            w.node.moveTo(r.left, r.top)
        }
        return w.node
    }

    private fun recordRun(
        scope: DrawScope,
        node: WalNode,
        run: LiveRun,
        cmds: List<DrawCmd>,
        host: WalHost,
    ) {
        val b = run.bounds
        val from = run.from
        val to = run.to
        val id = run.id
        node.version = run.version
        // a run opens no window shape and closes the clips it opens: its commands draw as a window's
        val run = cmds.subList(from, to)
        node.record(b.left, b.top, b.width, b.height, scope, scope.layoutDirection) {
            host.drawing.recording(node.kept) {
                translate(-b.left.toFloat(), -b.top.toFloat()) { drawWalWindow(run, host) }
            }
        }
        runsAgain++
        runAreaAgain += b.width.toLong() * b.height
        onRecord?.invoke(RUN, id)
    }

    @Suppress("LongParameterList") // the window, its plan and where it goes
    private fun recordWindow(
        scope: DrawScope,
        w: WindowNode,
        plan: WindowPlan,
        r: Rect,
        cmds: List<DrawCmd>,
        host: WalHost,
        overClear: Boolean,
    ) {
        val runs = plan.runs
        val nodes = runs.map { w.runs.getValue(it.id) }
        w.node.version = plan.version
        w.overClear = overClear
        w.node.record(r.left, r.top, r.width, r.height, scope, scope.layoutDirection) {
            host.drawing.recording(w.node.kept) {
                drawWalWindow(cmds, host, overClear = overClear, runs = runs) { k -> nodes[k].draw(this) }
            }
        }
        windowsAgain++
        onRecord?.invoke(WINDOW, plan.id)
    }

    private fun release(w: WindowNode) {
        w.node.release()
        for (node in w.runs.values) node.release()
        w.runs.clear()
    }

    /** Lets go of the kept windows and runs: the next frame drawn this way records everything again. */
    fun forget() {
        if (windows.isEmpty() && recorded.isEmpty()) return
        for (w in windows.values) release(w)
        windows.clear()
        recorded.clear()
        recordedSize = IntSize.Zero
    }

    /** After a draw that threw: nothing kept is trusted, so the next frame starts clean. */
    fun drop() = forget()

    /** Lets go of everything, the stack layer too, as the canvas goes. */
    fun release() {
        forget()
        stack?.let(releaseLayer)
        stack = null
    }

    companion object {
        /** What [onRecord] is told was recorded. */
        const val STACK = "stack"
        const val WINDOW = "window"
        const val RUN = "run"
    }
}

/**
 * One window's or one live run's drawing, at its place in what draws it: recorded once ([record])
 * and drawn as it is ([draw]) until recorded again. As a render node it clips to its bounds, so a
 * new recording redraws only them; without one ([hardware] false) it draws its content where it is
 * drawn, each time. What its content asks of the skin's drawing is held in [kept].
 */
internal class WalNode(
    hardware: Boolean,
) {
    private val render = if (hardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Render() else null
    private var content: (DrawScope.() -> Unit)? = null
    private var density: Density = Density(1f)
    private var direction = LayoutDirection.Ltr

    var left = 0
        private set
    var top = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    /** The plan's version of what it holds. */
    var version = Long.MIN_VALUE

    /** The last frame that drew it; the canvas's to keep. */
    var frame = 0L

    /** What its content holds of the skin's drawing. */
    val kept = WalDrawing.Recorded()

    /** Records [content] at ([left], [top]), [width] x [height], in the coordinates of what draws it. */
    @Suppress("LongParameterList") // where it goes and what it draws
    fun record(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        density: Density,
        direction: LayoutDirection,
        content: DrawScope.() -> Unit,
    ) {
        this.left = left
        this.top = top
        this.width = width
        this.height = height
        this.density = Density(density.density, density.fontScale)
        this.direction = direction
        this.content = content
        render?.record()
    }

    /** Moves it to ([left], [top]) without recording it again. */
    fun moveTo(
        left: Int,
        top: Int,
    ) {
        if (left == this.left && top == this.top) return
        this.left = left
        this.top = top
        render?.place()
    }

    /** Draws it into [scope] at its place. */
    fun draw(scope: DrawScope) {
        val canvas = scope.drawContext.canvas.nativeCanvas
        val render = render
        if (render != null && canvas.isHardwareAccelerated) {
            render.draw(canvas)
            return
        }
        val content = content ?: return
        scope.translate(left.toFloat(), top.toFloat()) {
            clipRect(0f, 0f, width.toFloat(), height.toFloat()) { content() }
        }
    }

    /** Lets go of what it recorded. */
    fun release() {
        render?.release()
        content = null
    }

    /** The render node it records into. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class Render {
        private val node = RenderNode("wal").apply { setClipToBounds(true) }
        private val scope = CanvasDrawScope()

        fun place() {
            node.setPosition(left, top, left + width, top + height)
        }

        fun record() {
            place()
            val canvas = node.beginRecording(width, height)
            try {
                scope.draw(density, direction, Canvas(canvas), Size(width.toFloat(), height.toFloat())) { content?.invoke(this) }
            } finally {
                node.endRecording()
            }
        }

        fun draw(canvas: android.graphics.Canvas) {
            // the platform lets go of a node's drawing when nothing draws it for a while
            if (!node.hasDisplayList()) record()
            canvas.drawRenderNode(node)
        }

        fun release() {
            node.discardDisplayList()
        }
    }
}
