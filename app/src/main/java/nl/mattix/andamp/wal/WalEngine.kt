// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.DrawPlanner
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.WindowPlan
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What the screen draws of a modern skin, and what it needs to know to place it and route touches:
 * one immutable snapshot, made on the engine thread after each change.
 */
@Suppress("LongParameterList") // one snapshot of all the screen reads of a frame
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
    /** Whether something slides or animates, so a stack changing size may not have come to rest yet. */
    val animating: Boolean,
    /** The engine's last log lines, newest last, as of this frame: a log line alone publishes no frame. */
    val log: List<String>,
    /**
     * What making this frame took on the engine thread, in ns: the tick, touch or task that changed
     * it, and the draw list; a popup menu it waited on is left out ([WalMenuWait.workClock]).
     */
    val buildNanos: Long = 0,
    /** Where the stack sits in the box of the last [WalEngine.fit], or null before the first one. */
    val layout: WalLayout? = null,
    /** The seq of the last touch when it landed on an object with a skin menu, else null; see [SkinRuntime.menuTarget]. */
    val menuTarget: Int? = null,
    /**
     * Whether the screen should tick the skin on every display frame: the engine's idle source says
     * something moves ([WalIdle.framesWanted]) and the skin has not gone static ([WalRest.wantsFrames]).
     */
    val wantsFrames: Boolean = true,
    /** The color theme the skin wears ([SkinRuntime.currentGammaSet]), or null when it has none. */
    val themeKey: String? = null,
    /**
     * What the screen may keep of each window of [stack] from earlier frames (see [WalLayers]), in
     * the same order; null when the engine did not plan the frame ([WalRender.layers] off).
     */
    val plans: List<WindowPlan>? = null,
)

/** What a skin says about resting, as [WalEngine] reads it each time it publishes a frame. */
internal data class WalIdle(
    /** Whether something moves that wants a frame on every display frame; see [SkinRuntime.framesWanted]. */
    val framesWanted: Boolean,
    /** When, in the runtime's clock ms, something next changes by itself, or null; see [SkinRuntime.nextDueAt]. */
    val nextDueAt: Long?,
    /** Whether the music is stopped, so the skin is woken at the resting rate; see [WalRest]. */
    val stopped: Boolean = false,
) {
    companion object {
        /** What [runtime] says. */
        fun of(runtime: SkinRuntime): WalIdle =
            WalIdle(runtime.framesWanted, runtime.nextDueAt(), stopped = runtime.player.status == Player.Status.STOPPED)
    }
}

/**
 * Runs one modern skin's runtime on a dedicated thread. The runtime is made, ticked, touched and
 * asked for its windows only there; the screen reads the [frame] the engine publishes after each
 * change, so the main thread only draws and never waits on the engine.
 *
 * Calls from the screen post work: at most one tick waits at a time, and pointer moves are merged
 * so only the latest waits. A touch or a tick publishes a frame when it is done, unless its change
 * is the first waiting and another touch or tick waits behind it, which then publishes both in one
 * frame: a press and its release, or a move and a tick. [onFrame] runs on the engine thread
 * after each new frame, and when a popup [menu] opens or closes.
 *
 * While the screen shows the skin ([awake]) and its frames want no ticks from the screen
 * ([WalFrame.wantsFrames]), the engine wakes the skin itself when it is next due, as [WalRest] sets
 * out; resting, under a menu and once closed it sets no wake.
 *
 * Nothing a skin does ends the engine thread. [start] reports whether the skin loaded. After that
 * every task is guarded: what it throws is logged and counted, a task that completes clears the
 * count, and [MAX_FAILURES] failures in a row tell [onBroken] that the skin stopped working. Frames
 * the screen fails to draw are counted the same way, apart from the tasks (see [drawFailed]).
 *
 * While [WalTiming] is on, the engine logs each tick and each frame it publishes, with what caused
 * it, the engine thread's name and the time each took, less any popup menu it waited on
 * ([WalMenuWait.workClock]); how long each touch waited for the engine thread; and the load.
 *
 * [idle] says, after each tick and each frame published, whether the skin wants frames and when it
 * is next due. The frame carries the first, less a skin gone static ([WalRest.wantsFrames]), and a
 * tick that changes it publishes a frame for that alone.
 */
internal class WalEngine(
    name: String,
    private val host: WalHost,
    private val idle: (SkinRuntime) -> WalIdle = WalIdle::of,
    private val onFrame: () -> Unit,
) {
    private val thread = HandlerThread("wal-$name").apply { start() }
    private val handler = Handler(thread.looper)

    /** Where a load that runs too long is noticed while the engine thread is still in it. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A tick the engine set itself; one waiting from the screen covers it. */
    private val wake =
        Runnable {
            if (!awake || host.menus.menu != null || !tickWaiting.compareAndSet(false, true)) return@Runnable
            wakes++
            runTick(WalCause.WAKE)
        }

    // the rule the wakes follow, and its clocks; touched on the engine thread, apart from cancel
    private val rest = WalRest(handler, wake)

    init {
        host.menus.onChange = onFrame
        // the skin as it stands when its menu opens, as Windows paints under a menu; a failure here
        // counts against the task that opened the menu. The skin stands still under it, so no wake
        // waits for after it.
        host.menus.beforeShow = {
            rest.cancel()
            if (!publishing) runCatching { publish(WalCause.MENU) }.onFailure { taskFailure = it }
        }
    }

    // touched only on the engine thread
    private var rt: SkinRuntime? = null
    private var baseW = 0
    private var publishing = false
    private val fitting = WalFitting()

    /** Works out what the screen may keep of each frame; the engine thread's. */
    private val planner = DrawPlanner()

    /** What [idle] said last in the running task, or null when it has not been asked yet. */
    private var lastIdle: WalIdle? = null

    /** What first asked for a frame that waits for the task behind it, or null when none waits; see [publish]. */
    private var pendingCause: WalCause? = null

    /** The work of the changes waiting for that frame, in ns. */
    private var pendingWork = 0L

    /** Touches and ticks posted that have not started yet; a touch's frame can wait for one of them. */
    private val publishersWaiting = AtomicInteger()

    /**
     * Sets the next wake, or drops the one set: one while the screen shows the skin, no menu is up
     * and its last frame wants no ticks from the screen. On the engine thread.
     */
    private val planWake =
        Runnable {
            val runtime = rt
            val idle =
                runtime
                    ?.takeIf { awake && !closed.get() && host.menus.menu == null && published.get()?.wantsFrames == false }
                    ?.let { runCatching { lastIdle ?: idle(it) }.getOrNull() }
            rest.plan(idle, SystemClock.uptimeMillis(), runtime?.now() ?: 0L)
        }

    /** Ticks the engine set itself so far; for the timing checks and tests. */
    @Volatile
    var wakes = 0
        private set

    /** Whether the engine has a wake set now; for tests. */
    internal val wakeSet: Boolean get() = rest.waiting

    /**
     * Whether the screen shows the skin now, as its look hands it on ([WalSurface.awake]); false
     * until it is set. While it is false the engine sets no wake, and drops the one it had.
     */
    @Volatile
    var awake = false
        set(value) {
            field = value
            // dropped at once, and again on the engine thread behind a wake it may be setting now
            if (!value) rest.cancel()
            handler.post(planWake)
        }

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

    /** Counts the ticks asked for a change the skin shows ([WalCause.KEY]); see [runTick]. */
    private val keyAsks = AtomicInteger()

    /**
     * Runs the tick that holds [tickWaiting] for a cause, then lets it go. A tick asked for a change
     * the skin shows after this one started asks for one more, so a resting skin, which nothing
     * else ticks, still shows the change.
     */
    private val runTick: (WalCause) -> Unit = { cause ->
        val asked = keyAsks.get()
        try {
            guarded("tick") { tick(cause) }
        } finally {
            tickWaiting.set(false)
            if (keyAsks.get() != asked) requestTick(WalCause.KEY)
        }
    }

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
            val made = runCatching { build().also { WalFitting.requireShowable(it, box) } }
            val loadMs = (System.nanoTime() - t0) / NANOS_PER_MS
            if (WalTiming.on) Log.d(WalTiming.TAG, "load $loadMs ms on ${thread.name}")
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

    /**
     * Puts the loaded [runtime] on, fits it to the box asked for, publishes its first frame and
     * sets its first wake; what that threw, or null.
     */
    private fun firstFrame(runtime: SkinRuntime): Throwable? {
        rt = runtime
        return runCatching {
            baseW = WalFitting.baseWOf(runtime)
            applyFit(runtime)
            publish(WalCause.FIT)
            planWake.run()
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
     * false; the timing log names that frame by [cause]. A touch's frame can wait for a touch or a
     * tick behind it, which publishes both (see [publish]). Before the skin is on, or when it did
     * not start, it does nothing.
     */
    fun post(
        publish: Boolean = true,
        cause: WalCause = WalCause.TASK,
        task: (SkinRuntime) -> Unit,
    ) {
        val merge = publish && cause == WalCause.TOUCH
        if (merge) publishersWaiting.incrementAndGet()
        handler.post {
            if (merge) publishersWaiting.decrementAndGet()
            guarded("engine") {
                val runtime = rt ?: return@guarded
                val t0 = host.menus.workClock
                task(runtime)
                if (publish) publish(cause, host.menus.workClock - t0, merge = merge)
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
                if (applyFit(runtime)) publish(WalCause.FIT)
            }
        }
    }

    /** Applies the box asked for last to [runtime], unless it is the one applied; true when it changed. */
    private fun applyFit(runtime: SkinRuntime): Boolean {
        val fit = wantedFit ?: return false
        if (fit == appliedFit) return false
        appliedFit = fit
        fitting.apply(runtime, fit, baseW)
        return true
    }

    /**
     * Asks for a tick, which the timing log names by [cause]. One running or waiting covers this
     * request, so a slow tick has none queued behind it, and none is asked while a menu is up. A
     * change the skin shows ([WalCause.KEY]) that comes while a tick runs gets one more tick after
     * it, as that tick may have read the player before the change.
     */
    fun requestTick(cause: WalCause = WalCause.TICK) {
        // the skin stands still under its menu, and no ticks wait to run in a burst after it
        if (host.menus.menu != null) return
        if (cause == WalCause.KEY) keyAsks.incrementAndGet()
        if (!tickWaiting.compareAndSet(false, true)) return
        publishersWaiting.incrementAndGet()
        handler.post {
            publishersWaiting.decrementAndGet()
            runTick(cause)
        }
    }

    /**
     * Ticks the skin, and publishes a frame when what it shows changed, when an animation started or
     * ended, when a frame waits for this tick, when whether the skin wants frames from the screen
     * changed, or when the skin's options changed. A log line alone publishes nothing.
     */
    private fun tick(cause: WalCause) {
        val runtime = rt ?: return
        val now = SystemClock.uptimeMillis()
        rest.ticked(now, fromScreen = cause == WalCause.TICK)
        val t0 = host.menus.workClock
        val changed = runtime.tick()
        val tickNanos = host.menus.workClock - t0
        val steps = runtime.scriptSteps
        if (changed) rest.changedAt = now
        val idle = idle(runtime).also { lastIdle = it }
        val shown = published.get()
        // an animation that ends on a step that moves nothing still changes what the screen paces by,
        // and so does a skin that starts or stops wanting frames, or goes static
        val due =
            changed || pendingCause != null || runtime.animating != shown?.animating ||
                rest.wantsFrames(idle, now) != shown?.wantsFrames ||
                shown?.layout?.let { it.skinOptions.version != runtime.configVersion } == true
        val t1 = host.menus.workClock
        val drew = due && publish(cause, tickNanos, merge = true, idleNow = idle)
        val drawNanos = if (drew) host.menus.workClock - t1 else 0L
        // publish logs the line of a frame it published
        if (!drew && WalTiming.on) Log.d(WalTiming.TAG, WalTiming.line(thread.name, tickNanos, 0, cause))
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
        publishersWaiting.incrementAndGet()
        handler.post {
            publishersWaiting.decrementAndGet()
            guarded("move") {
                val runtime = rt ?: return@guarded
                val (mx, my) = moveWaiting.getAndSet(null) ?: return@guarded
                if (WalTiming.on) Log.d(WalTiming.TAG, "move waited ${System.nanoTime() - at} ns")
                val t0 = host.menus.workClock
                runtime.pointerMove(mx, my)
                publish(WalCause.TOUCH, host.menus.workClock - t0, merge = true)
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

    /**
     * Right-clicks the object and point touch [seq] went down on, or with a [choice] that object of
     * [SkinRuntime.skinMenuChoices], for its skin menu; see [SkinRuntime.rightClick].
     */
    fun rightClick(
        seq: Int,
        choice: Int? = null,
    ) = postTouch("right click") {
        flushMove(it)
        it.rightClick(seq, choice)
    }

    /** [post] for a touch; the timing log says how long it waited for the engine thread. */
    private fun postTouch(
        what: String,
        task: (SkinRuntime) -> Unit,
    ) {
        val at = System.nanoTime()
        post(cause = WalCause.TOUCH) { runtime ->
            if (WalTiming.on) Log.d(WalTiming.TAG, "$what waited ${System.nanoTime() - at} ns")
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
        rest.cancel()
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
     * counts as a failure; a task that completes clears the count, unless its frame waits for the
     * next task (see [publish]). The first [MAX_FAILURES] in a
     * row are logged and the rest are not, so a skin failing every frame does not fill the log.
     * A frame left waiting for a task that published none is published once no such task waits,
     * and the next wake is set after every task.
     */
    private fun guarded(
        what: String,
        body: () -> Unit,
    ) {
        taskFailure = null
        lastIdle = null
        val error =
            runCatching {
                try {
                    body()
                    pendingCause?.let { if (publishersWaiting.get() == 0) publish(it) }
                    rt?.let { onTask?.invoke(it) }
                } finally {
                    planWake.run()
                }
            }.exceptionOrNull() ?: taskFailure
        if (error == null) {
            // a change still waiting for its frame has not been drawn yet
            if (pendingCause == null) failures = 0
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
     * Once the skin has a box, [WalFitting.refit] first notes the windows now open. [workNanos] is
     * the work that changed the frame (a tick, a touch, a task), for the frame's build time; the
     * timing log names the frame by [cause]. A draw list that fails, or a stack grown too large to
     * draw (see [WalScale.unfit]), throws: the screen keeps the last picture, and the task counts a
     * failure.
     *
     * With [merge], a change that is the first waiting publishes nothing while a touch or a tick
     * waits to start: that one publishes both in one frame, named by the first.
     * Every frame takes the changes waiting with it. [idleNow] is what [idle] said after the work,
     * when it was asked already. A change other than a tick's counts as one for [WalRest.changedAt].
     */
    private fun publish(
        cause: WalCause,
        workNanos: Long = 0,
        merge: Boolean = false,
        idleNow: WalIdle? = null,
    ): Boolean {
        val runtime = rt ?: return false
        val now = SystemClock.uptimeMillis()
        // a tick says itself whether it changed anything
        if (cause != WalCause.TICK && cause != WalCause.WAKE && cause != WalCause.KEY) rest.changedAt = now
        val first = pendingCause == null
        if (first) pendingCause = cause
        pendingWork += workNanos
        if (merge && first && publishersWaiting.get() > 0) return false
        val named = if (merge) pendingCause ?: cause else cause
        val work = pendingWork
        pendingCause = null
        pendingWork = 0
        val t0 = host.menus.workClock
        appliedFit?.let { fitting.refit(runtime, it, baseW) }
        publishing = true
        val planned = planner.planNanos
        val fullPlans = planner.fullPlans
        val (stack, plans) =
            try {
                drawPlanned(runtime, planner, now)
            } finally {
                publishing = false
            }
        val layout = runtime.mainLayout
        val mainSize = layout?.size
        val size = sizeOf(stack)
        if (mainSize != null) WalScale.unfit(mainSize, size, baseW, box = null)?.let { error(it) }
        val windowOpen = runtime.topWindow != null
        val idle = idleNow ?: idle(runtime)
        lastIdle = idle
        val wantsFrames = rest.wantsFrames(idle, now)
        published.set(
            WalFrame(
                stack = stack,
                mainSize = mainSize,
                contentRight = layout?.contentRight() ?: 0,
                baseW = baseW,
                windowOpen = windowOpen,
                animating = runtime.animating,
                log = host.logSnapshot(),
                buildNanos = work + host.menus.workClock - t0,
                layout =
                    appliedFit?.takeIf { mainSize != null }?.let {
                        fitting.layout(runtime, it, baseW, size, runtime.drawnSize, windowOpen, wantsFrames)
                    },
                menuTarget = runtime.menuTarget,
                wantsFrames = wantsFrames,
                themeKey = runtime.currentGammaSet,
                plans = plans,
            ),
        )
        if (WalTiming.on) {
            val plan = plans?.let { planner.planNanos - planned }
            val fromStart = planner.fullPlans != fullPlans
            Log.d(WalTiming.TAG, WalTiming.line(thread.name, work, host.menus.workClock - t0, named, plan, fromStart))
        }
        onFrame()
        return true
    }

    companion object {
        /** Failures in a row after which a skin counts as stopped working. */
        const val MAX_FAILURES = 3

        private const val NANOS_PER_MS = 1_000_000L

        private fun overrunFailure(budgetMs: Long) = TimeoutException("took longer than $budgetMs ms to load")

        /** How far right and down the windows of [stack] reach. */
        private fun sizeOf(stack: List<Pair<Rect, List<DrawCmd>>>): Pair<Int, Int> =
            (stack.maxOfOrNull { it.first.right } ?: 0) to (stack.maxOfOrNull { it.first.bottom } ?: 0)
    }
}

/**
 * [runtime]'s windows as drawWindows gives them, with a plan of each by [planner] at [now] while
 * [WalRender.layers] is on, so the screen draws again only what changed (see [WalLayers]).
 */
private fun drawPlanned(
    runtime: SkinRuntime,
    planner: DrawPlanner,
    now: Long,
): Pair<List<Pair<Rect, List<DrawCmd>>>, List<WindowPlan>?> =
    if (WalRender.layers) planner.draw(runtime, now).let { it.stack to it.plans } else runtime.drawWindows() to null
