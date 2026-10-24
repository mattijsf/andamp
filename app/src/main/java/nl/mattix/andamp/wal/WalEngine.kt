// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.BuildConfig
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * What the screen draws of a modern skin, and what it needs to know to place it and route touches:
 * one immutable snapshot, made on the engine thread after each change.
 */
internal class WalFrame(
    /** The windows and their commands, as `SkinRuntime.drawWindows` gives them. */
    val stack: List<Pair<Rect, List<DrawCmd>>>,
    /** The main window's size, or null when the skin has none. */
    val mainSize: Pair<Int, Int>?,
    /** How far right the main window's content reaches; the stack is centered by it. */
    val contentRight: Int,
    /** The main window's visible width at the start, which sets the scale; 0 when the window resizes both ways. */
    val baseW: Int,
    /** Whether a skin window is open over the main one, for Back. */
    val windowOpen: Boolean,
    /** Whether something slides or animates, so the screen asks for a tick every display frame. */
    val animating: Boolean,
    /** The engine's last log lines, newest last. */
    val log: List<String>,
    /** What making this frame took on the engine thread, in ns: the tick that changed it, if any, and the draw list. */
    val buildNanos: Long = 0,
    /** Where the stack sits in the box of the last [WalEngine.fit], or null before the first one. */
    val layout: WalLayout? = null,
    /** The seq of the last touch when it landed on an object with a skin menu, else null; see [SkinRuntime.menuTarget]. */
    val menuTarget: Int? = null,
)

/**
 * Runs one modern skin's runtime on a dedicated thread. The runtime is made, ticked, touched and
 * asked for its windows only there; the screen reads the [frame] the engine publishes after each
 * change, so the main thread only draws and never waits on the engine.
 *
 * Calls from the screen post work: at most one tick waits at a time, and pointer moves are merged
 * so only the latest waits. [onFrame] runs on the engine thread after each new frame, and when a
 * popup [menu] opens or closes.
 *
 * Nothing a skin does ends the engine thread. [start] reports whether the skin loaded. After that
 * every task is guarded: what it throws is logged and counted, a task that completes clears the
 * count, and [MAX_FAILURES] failures in a row tell [onBroken] that the skin stopped working. Frames
 * the screen fails to draw are counted the same way, apart from the tasks (see [drawFailed]).
 *
 * A debug build logs timings under [TIMING_TAG]: each tick's time and its draw list's, with the
 * engine thread's name, how long each touch waited for the engine thread, and the load.
 */
internal class WalEngine(
    name: String,
    private val host: WalHost,
    private val onFrame: () -> Unit,
) {
    private val thread = HandlerThread("wal-$name").apply { start() }
    private val handler = Handler(thread.looper)

    /** Where a load that runs too long is noticed while the engine thread is still in it. */
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        host.menus.onChange = onFrame
        // the skin as it stands when its menu opens, as Windows paints under a menu; a failure here
        // counts against the task that opened the menu
        host.menus.beforeShow = { if (!publishing) runCatching { publish() }.onFailure { taskFailure = it } }
    }

    // touched only on the engine thread
    private var rt: SkinRuntime? = null
    private var baseW = 0
    private var seenVersion = -1
    private var publishing = false

    /** The box applied to the runtime last; see [fit]. */
    private var appliedFit: WalFit? = null

    /** Tasks that failed in a row. */
    private var failures = 0

    /** Frames the screen failed to draw in a row; see [drawFailed]. */
    private var drawFailures = 0

    /** [onBroken] has been called. */
    private var broken = false

    /** What the running task caught without throwing it (the redraw before a menu), so it still counts. */
    private var taskFailure: Throwable? = null

    /** The box asked for last, from any thread; the engine thread applies it. */
    @Volatile
    private var wantedFit: WalFit? = null

    private val published = AtomicReference<WalFrame?>(null)
    private val tickWaiting = AtomicBoolean(false)
    private val moveWaiting = AtomicReference<Pair<Int, Int>?>(null)

    /** The start has been reported, or the engine closed before it was: nothing more is reported. */
    private val startDecided = AtomicBoolean(false)

    /** [close] was called: a start report or a break still to come is not passed on. */
    private val closed = AtomicBoolean(false)

    // what [start] was given, read on the engine thread and, for a load that runs too long, on main
    @Volatile
    private var onStart: (Throwable?) -> Unit = {}

    @Volatile
    private var budgetMs = WalBuild.BUDGET_MS

    /** Reports a load still running when its time is up; what it loads afterward is dropped. */
    private val overrun = Runnable { if (decideStart()) reportStart(overrunFailure(budgetMs)) }

    /** The last frame published, or null before the skin has loaded. */
    val frame: WalFrame? get() = published.get()

    /** The popup menu the skin waits on, for the screen to show, or null. The engine stands still meanwhile. */
    val menu: SkinMenu? get() = host.menus.menu

    /** The listener picked command [id] from [menu], or 0 for nothing. */
    fun answerMenu(
        menu: SkinMenu,
        id: Int,
    ) = host.menus.answer(menu, id)

    /** Closes the popup menu waiting now, if any, with nothing picked. */
    fun dismissMenu() = host.menus.dismiss()

    /**
     * Called on the engine thread after each tick: the tick's and the draw list's time in ns (0 when
     * nothing was published), whether a frame was published, and the script steps the tick ran.
     */
    @Volatile
    var onTicked: ((runtime: SkinRuntime, tickNanos: Long, drawNanos: Long, published: Boolean, steps: Long) -> Unit)? = null

    /**
     * Called once, on the engine thread, when [MAX_FAILURES] tasks in a row have failed: the skin
     * stopped working. The engine carries on; the caller decides what follows.
     */
    @Volatile
    var onBroken: (() -> Unit)? = null

    /**
     * Called on the engine thread after each task that ran with the skin on (a tick, a touch, a fit),
     * with its runtime; what it throws counts against that task.
     */
    @Volatile
    var onTask: ((runtime: SkinRuntime) -> Unit)? = null

    /**
     * Makes and loads the runtime on the engine thread with [build], then publishes the first frame.
     * Called once. [onStart] hears how it went, once: null when the skin is on, else why it is not.
     *
     * A build that throws fails, as do a skin with no main window, one too large to show (see
     * [WalScale.unfit], with the [box] the skin goes in when known), one whose first frame cannot be
     * made, and a load that takes longer than [budgetMs]. A load still running when that time is up
     * is reported from the main thread, and what it loads afterward is dropped; every other report
     * comes from the engine thread. After a failure the engine thread runs on with no skin and its
     * tasks do nothing. A start that has not reported when [close] is called reports nothing, unless
     * its report is already being made on the engine thread.
     */
    fun start(
        budgetMs: Long = WalBuild.BUDGET_MS,
        box: IntSize? = null,
        onStart: (failure: Throwable?) -> Unit = {},
        build: () -> SkinRuntime,
    ) {
        this.budgetMs = budgetMs
        this.onStart = onStart
        mainHandler.postDelayed(overrun, budgetMs)
        handler.post {
            val t0 = System.nanoTime()
            val made = runCatching { build().also { Fitting.requireShowable(it, box) } }
            val loadMs = (System.nanoTime() - t0) / NANOS_PER_MS
            if (TIMING) Log.d(TIMING_TAG, "load $loadMs ms on ${thread.name}")
            val overran = loadMs > budgetMs
            // the time ran out (and was reported) or the engine closed while it loaded
            if (!decideStart() || closed.get()) return@post
            val failure =
                made.exceptionOrNull()
                    ?: overrunFailure(budgetMs).takeIf { overran }
                    ?: firstFrame(made.getOrThrow())
            reportStart(failure)
        }
    }

    /** Puts the loaded [runtime] on, fits it to the box asked for, and publishes its first frame; what that threw, or null. */
    private fun firstFrame(runtime: SkinRuntime): Throwable? {
        rt = runtime
        return runCatching {
            baseW = Fitting.baseWOf(runtime)
            applyFit(runtime)
            publish()
        }.exceptionOrNull()?.also { rt = null }
    }

    /** Takes the one start report for the caller; false when it was taken already or the engine closed. */
    private fun decideStart(): Boolean {
        if (!startDecided.compareAndSet(false, true)) return false
        mainHandler.removeCallbacks(overrun)
        return true
    }

    private fun reportStart(failure: Throwable?) {
        if (failure != null) {
            Log.w(WalHost.TAG, "start", failure)
            host.log("start failed: $failure")
        }
        // closed while the first frame was made
        if (closed.get()) return
        runCatching { onStart(failure) }.onFailure { e -> Log.w(WalHost.TAG, "start report", e) }
    }

    /**
     * Runs [task] with the runtime on the engine thread, then publishes a frame unless [publish] is
     * false. Before the skin is on, or when it did not start, it does nothing.
     */
    fun post(
        publish: Boolean = true,
        task: (SkinRuntime) -> Unit,
    ) {
        handler.post {
            guarded("engine") {
                val runtime = rt ?: return@guarded
                task(runtime)
                if (publish) publish()
            }
        }
    }

    /**
     * Runs [task] on the engine thread once the work posted before it is done, with the runtime, or
     * with null when the skin is not on (it has not loaded yet, or did not start); publishes nothing.
     * For work a caller waits on either way.
     */
    fun postAlways(task: (SkinRuntime?) -> Unit) {
        handler.post { guarded("engine") { task(rt) } }
    }

    /**
     * Runs [task] with the runtime on the engine thread once [delayMs] have passed, in place of a
     * task still waiting under the same [token]; publishes nothing. Before the skin is on, or when
     * it did not start, it does nothing. [cancel] drops it.
     */
    fun postLater(
        token: Any,
        delayMs: Long,
        task: (SkinRuntime) -> Unit,
    ) {
        handler.removeCallbacksAndMessages(token)
        handler.postAtTime({ guarded("engine") { rt?.let(task) } }, token, SystemClock.uptimeMillis() + delayMs)
    }

    /** Drops the tasks still waiting under [token]; see [postLater]. */
    fun cancel(token: Any) = handler.removeCallbacksAndMessages(token)

    /**
     * The box the skin goes in, on the screen. On the engine thread it makes a window the listener
     * resizes both ways (Bento) fill the box, as maximized; gives the skin the box in skin pixels
     * ([SkinRuntime.viewSize]); while upright stretches the last window under the main one to the
     * height left, and sideways keeps the height each window has; a compact box ([WalFit.compact])
     * keeps every height, and the window that resizes both ways at its smallest height; and lets a
     * touch 24 dp beside an object still mean it ([SkinRuntime.touchSlop]). Each frame then carries
     * a [WalLayout] for this box (see [WalScale.place]). A box asked for before the skin has loaded
     * is applied before its first frame. A fit equal to the one applied, or a box with no area, does
     * nothing.
     */
    fun fit(fit: WalFit) {
        if (fit.widthPx <= 0 || fit.heightPx <= 0) return
        wantedFit = fit
        handler.post {
            guarded("fit") {
                val runtime = rt ?: return@guarded
                if (applyFit(runtime)) publish()
            }
        }
    }

    /** Applies the box asked for last to [runtime], unless it is the one applied; true when it changed. */
    private fun applyFit(runtime: SkinRuntime): Boolean {
        val fit = wantedFit ?: return false
        if (fit == appliedFit) return false
        appliedFit = fit
        Fitting.apply(runtime, fit, baseW)
        return true
    }

    /**
     * Asks for a tick. One running or waiting covers this request, so a slow tick has none queued
     * behind it, and none is asked while a menu is up.
     */
    fun requestTick() {
        // the skin stands still under its menu, and no ticks wait to run in a burst after it
        if (host.menus.menu != null) return
        if (!tickWaiting.compareAndSet(false, true)) return
        handler.post {
            try {
                guarded("tick", ::tick)
            } finally {
                tickWaiting.set(false)
            }
        }
    }

    private fun tick() {
        val runtime = rt ?: return
        val t0 = System.nanoTime()
        val changed = runtime.tick()
        val tickNanos = System.nanoTime() - t0
        val steps = runtime.scriptSteps
        // an animation that ends on a step that moves nothing still changes what the screen paces by
        val due = changed || host.version != seenVersion || runtime.animating != published.get()?.animating
        val t1 = System.nanoTime()
        val drew = due && publish(tickNanos)
        val drawNanos = if (drew) System.nanoTime() - t1 else 0L
        if (TIMING) Log.d(TIMING_TAG, "tick ${thread.name}: tick $tickNanos ns, publish $drawNanos ns")
        onTicked?.invoke(runtime, tickNanos, drawNanos, drew, steps)
    }

    /** A finger down at a point of the stack; [seq] names the touch, for [rightClick]. */
    fun pointerDown(
        x: Int,
        y: Int,
        seq: Int = 0,
    ) = postTouch("down") { it.pointerDown(x, y, seq) }

    /** A pointer move; moves that arrive faster than the engine handles them are merged into the latest. */
    fun pointerMove(
        x: Int,
        y: Int,
    ) {
        if (moveWaiting.getAndSet(x to y) != null) return
        val at = System.nanoTime()
        handler.post {
            guarded("move") {
                val runtime = rt ?: return@guarded
                val (mx, my) = moveWaiting.getAndSet(null) ?: return@guarded
                if (TIMING) Log.d(TIMING_TAG, "move waited ${System.nanoTime() - at} ns")
                runtime.pointerMove(mx, my)
                publish()
            }
        }
    }

    fun pointerUp(
        x: Int,
        y: Int,
    ) = postTouch("up") {
        flushMove(it)
        it.pointerUp(x, y)
    }

    /** Ends the press without a release, as when the app takes the touch: the control gives no click. */
    fun pointerCancel() =
        postTouch("cancel") {
            flushMove(it)
            it.pointerCancel()
        }

    /**
     * A double tap: the double-click action of what is under it, such as a title bar's switch to its
     * shade. [seq] names the touch, as [pointerDown]'s does.
     */
    fun doubleTap(
        x: Int,
        y: Int,
        seq: Int = 0,
    ) = postTouch("double tap") {
        flushMove(it)
        it.doubleTap(x, y, seq)
    }

    /** Right-clicks the object and point touch [seq] went down on, for its skin menu; see [SkinRuntime.rightClick]. */
    fun rightClick(seq: Int) =
        postTouch("right click") {
            flushMove(it)
            it.rightClick(seq)
        }

    /** [post] for a touch; a debug build logs how long it waited for the engine thread. */
    private fun postTouch(
        what: String,
        task: (SkinRuntime) -> Unit,
    ) {
        val at = System.nanoTime()
        post { runtime ->
            if (TIMING) Log.d(TIMING_TAG, "$what waited ${System.nanoTime() - at} ns")
            task(runtime)
        }
    }

    /**
     * Drawing the last frame on the screen threw [error]. The engine thread counts these apart from
     * its tasks, so a task that completes does not clear them: [MAX_FAILURES] in a row, with no frame
     * drawn in between (see [drawWorked]), tell [onBroken] that the skin stopped working, as failing
     * tasks do. [onBroken] is called once either way.
     */
    fun drawFailed(error: Throwable) {
        handler.post {
            drawFailures++
            if (drawFailures <= MAX_FAILURES) {
                Log.w(WalHost.TAG, "draw", error)
                host.log("draw: $error")
            }
            if (drawFailures >= MAX_FAILURES) breakOnce()
        }
    }

    /** A frame was drawn after one that failed: the count of failed draws starts over. */
    fun drawWorked() {
        handler.post { drawFailures = 0 }
    }

    /**
     * Ends the engine thread once the work already posted is done; a menu waiting now closes first.
     * A start still running reports nothing (see [start]), and failures in that work call no
     * [onBroken].
     */
    fun close() {
        closed.set(true)
        if (startDecided.compareAndSet(false, true)) mainHandler.removeCallbacks(overrun)
        host.menus.close()
        thread.quitSafely()
    }

    /** A move still waiting goes first, so a release lands where the finger last was. */
    private fun flushMove(runtime: SkinRuntime) {
        moveWaiting.getAndSet(null)?.let { (mx, my) -> runtime.pointerMove(mx, my) }
    }

    /**
     * Runs [body] as one engine task. What it throws, or caught on the way (see [taskFailure]),
     * counts as a failure; a task that completes clears the count. The first [MAX_FAILURES] in a
     * row are logged and the rest are not, so a skin failing every frame does not fill the log.
     */
    private fun guarded(
        what: String,
        body: () -> Unit,
    ) {
        taskFailure = null
        val error =
            runCatching {
                body()
                rt?.let { onTask?.invoke(it) }
            }.exceptionOrNull() ?: taskFailure
        if (error == null) {
            failures = 0
            return
        }
        failures++
        if (failures <= MAX_FAILURES) {
            Log.w(WalHost.TAG, what, error)
            host.log("$what: $error")
        }
        if (failures >= MAX_FAILURES) breakOnce()
    }

    /** Tells [onBroken] that the skin stopped working, the first time only, and not once the engine is closed. */
    private fun breakOnce() {
        if (broken || closed.get()) return
        broken = true
        runCatching { onBroken?.invoke() }.onFailure { e -> Log.w(WalHost.TAG, "onBroken", e) }
    }

    /**
     * Builds a new frame from the runtime and hands it to the screen; true when one was published.
     * [tickNanos] is the tick that changed it, for the frame's build time. A draw list that fails,
     * or a stack grown too large to draw (see [WalScale.unfit]), throws: the screen keeps the last
     * picture, and the task counts a failure.
     */
    private fun publish(tickNanos: Long = 0): Boolean {
        val runtime = rt ?: return false
        seenVersion = host.version
        val t0 = System.nanoTime()
        publishing = true
        val stack =
            try {
                runtime.drawWindows()
            } finally {
                publishing = false
            }
        val layout = runtime.mainLayout
        val mainSize = layout?.size
        val size = sizeOf(stack)
        if (mainSize != null) WalScale.unfit(mainSize, size, baseW, box = null)?.let { error(it) }
        val windowOpen = runtime.topWindow != null
        published.set(
            WalFrame(
                stack = stack,
                mainSize = mainSize,
                contentRight = layout?.contentRight() ?: 0,
                baseW = baseW,
                windowOpen = windowOpen,
                animating = runtime.animating,
                log = host.logSnapshot(),
                buildNanos = tickNanos + System.nanoTime() - t0,
                layout =
                    appliedFit?.takeIf { mainSize != null }?.let {
                        Fitting.layout(runtime, it, baseW, size, runtime.drawnSize, windowOpen)
                    },
                menuTarget = runtime.menuTarget,
            ),
        )
        onFrame()
        return true
    }

    companion object {
        /** Failures in a row after which a skin counts as stopped working. */
        const val MAX_FAILURES = 3

        private const val NANOS_PER_MS = 1_000_000L

        /** The log tag of a debug build's timings. */
        const val TIMING_TAG = "WalTiming"

        /** Whether this build logs timings. */
        private val TIMING = BuildConfig.DEBUG

        private fun overrunFailure(budgetMs: Long) = TimeoutException("took longer than $budgetMs ms to load")

        /** How far right and down the windows of [stack] reach. */
        private fun sizeOf(stack: List<Pair<Rect, List<DrawCmd>>>): Pair<Int, Int> =
            (stack.maxOfOrNull { it.first.right } ?: 0) to (stack.maxOfOrNull { it.first.bottom } ?: 0)
    }
}

/** How the box a skin goes in on the screen reaches its runtime, and where its stack then sits; on the engine thread. */
private object Fitting {
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

    /** Gives [runtime] the box of [fit], as [WalEngine.fit] tells; [baseW] as [baseWOf] gives it. */
    fun apply(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
    ) {
        val layout = runtime.mainLayout ?: return
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
     * Where a stack of [size] sits in [fit]'s box, worked out again for each frame, since a window
     * opening changes the stack. The layout's stack is the part of it the windows show ([drawn],
     * see [SkinRuntime.drawnSize]), so the window rect, the touch area and the visualizer window
     * docked under the skin go by where the skin ends. A touch 24 dp beside an object still means
     * it at that scale.
     */
    fun layout(
        runtime: SkinRuntime,
        fit: WalFit,
        baseW: Int,
        size: Pair<Int, Int>,
        drawn: Pair<Int, Int>,
        windowOpen: Boolean,
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
}
