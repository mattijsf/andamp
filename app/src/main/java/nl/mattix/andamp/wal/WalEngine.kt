// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.compose.ui.unit.IntSize
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
 * count, and [MAX_FAILURES] failures in a row tell [onBroken] that the skin stopped working.
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

    /** Tasks that failed in a row. */
    private var failures = 0

    /** [onBroken] has been called. */
    private var broken = false

    /** What the running task caught without throwing it (the redraw before a menu), so it still counts. */
    private var taskFailure: Throwable? = null

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
    private val overrun = Runnable { if (decideStart()) reportStart(overrunFailure()) }

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
            val made = runCatching { build().also { requireShowable(it, box) } }
            val overran = (System.nanoTime() - t0) / NANOS_PER_MS > budgetMs
            // the time ran out (and was reported) or the engine closed while it loaded
            if (!decideStart() || closed.get()) return@post
            val failure =
                made.exceptionOrNull()
                    ?: overrunFailure().takeIf { overran }
                    ?: firstFrame(made.getOrThrow())
            reportStart(failure)
        }
    }

    /** Throws unless the screen can show [runtime]: it has a main window, and a stack a phone can draw. */
    private fun requireShowable(
        runtime: SkinRuntime,
        box: IntSize?,
    ) {
        val layout = runtime.mainLayout ?: error("no main layout")
        WalScale.unfit(layout.size, runtime.stackSize, baseWOf(runtime), box)?.let { error(it) }
    }

    /** Puts the loaded [runtime] on and publishes its first frame; what that threw, or null. */
    private fun firstFrame(runtime: SkinRuntime): Throwable? {
        rt = runtime
        return runCatching {
            baseW = baseWOf(runtime)
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

    private fun overrunFailure() = TimeoutException("took longer than $budgetMs ms to load")

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
        onTicked?.invoke(runtime, tickNanos, if (drew) System.nanoTime() - t1 else 0L, drew, steps)
    }

    fun pointerDown(
        x: Int,
        y: Int,
    ) = post { it.pointerDown(x, y) }

    /** A pointer move; moves that arrive faster than the engine handles them are merged into the latest. */
    fun pointerMove(
        x: Int,
        y: Int,
    ) {
        if (moveWaiting.getAndSet(x to y) != null) return
        handler.post {
            guarded("move") {
                val runtime = rt ?: return@guarded
                val (mx, my) = moveWaiting.getAndSet(null) ?: return@guarded
                runtime.pointerMove(mx, my)
                publish()
            }
        }
    }

    fun pointerUp(
        x: Int,
        y: Int,
    ) = post {
        flushMove(it)
        it.pointerUp(x, y)
    }

    /** Ends the press without a release, as when the app takes the touch: the control gives no click. */
    fun pointerCancel() =
        post {
            flushMove(it)
            it.pointerCancel()
        }

    /** A double tap: the double-click action of what is under it, such as a title bar's switch to its shade. */
    fun doubleTap(
        x: Int,
        y: Int,
    ) = post {
        flushMove(it)
        it.doubleTap(x, y)
    }

    fun longPress(
        x: Int,
        y: Int,
    ) = post {
        flushMove(it)
        it.longPress(x, y)
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
        val error = runCatching(body).exceptionOrNull() ?: taskFailure
        if (error == null) {
            failures = 0
            return
        }
        failures++
        if (failures <= MAX_FAILURES) {
            Log.w(WalHost.TAG, what, error)
            host.log("$what: $error")
        }
        if (failures >= MAX_FAILURES && !broken && !closed.get()) {
            broken = true
            runCatching { onBroken?.invoke() }.onFailure { e -> Log.w(WalHost.TAG, "onBroken", e) }
        }
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
        if (mainSize != null) WalScale.unfit(mainSize, sizeOf(stack), baseW, box = null)?.let { error(it) }
        published.set(
            WalFrame(
                stack = stack,
                mainSize = mainSize,
                contentRight = layout?.contentRight() ?: 0,
                baseW = baseW,
                windowOpen = runtime.topWindow != null,
                animating = runtime.animating,
                log = host.logSnapshot(),
                buildNanos = tickNanos + System.nanoTime() - t0,
            ),
        )
        onFrame()
        return true
    }

    companion object {
        /** Failures in a row after which a skin counts as stopped working. */
        const val MAX_FAILURES = 3

        private const val NANOS_PER_MS = 1_000_000L

        /**
         * The main window's visible width at the start, which sets the scale (MMD3 keeps room on its
         * right for a panel that slides out); fixed, so the size does not jump when that panel opens.
         * A window the user resizes both ways (Bento) is sized to the screen and scales from its size: 0.
         */
        private fun baseWOf(runtime: SkinRuntime): Int =
            runtime.mainLayout?.takeUnless { it.resizesHorizontally() && it.resizesVertically() }?.contentRight() ?: 0

        /** How far right and down the windows of [stack] reach. */
        private fun sizeOf(stack: List<Pair<Rect, List<DrawCmd>>>): Pair<Int, Int> =
            (stack.maxOfOrNull { it.first.right } ?: 0) to (stack.maxOfOrNull { it.first.bottom } ?: 0)
    }
}
