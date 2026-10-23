// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
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
 */
internal class WalEngine(
    name: String,
    private val host: WalHost,
    private val onFrame: () -> Unit,
) {
    private val thread = HandlerThread("wal-$name").apply { start() }
    private val handler = Handler(thread.looper)

    init {
        host.menus.onChange = onFrame
        // the skin as it stands when its menu opens, as Windows paints under a menu
        host.menus.beforeShow = { if (!publishing) publish() }
    }

    // touched only on the engine thread
    private var rt: SkinRuntime? = null
    private var baseW = 0
    private var seenVersion = -1
    private var publishing = false

    /** The draw list failed last time; logged once until it works again. */
    private var drawFailing = false

    private val published = AtomicReference<WalFrame?>(null)
    private val tickWaiting = AtomicBoolean(false)
    private val moveWaiting = AtomicReference<Pair<Int, Int>?>(null)

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

    /** Makes and loads the runtime on the engine thread with [build], then publishes the first frame. */
    fun start(build: () -> SkinRuntime) {
        handler.post {
            val made = build()
            rt = made
            // the main window's visible width at the start sets the scale (MMD3 keeps room on its right
            // for a panel that slides out); fixed, so the size does not jump when that panel opens.
            // A window the user resizes both ways (Bento) is sized to the screen and scales from its size
            baseW = made.mainLayout?.takeUnless { it.resizesHorizontally() && it.resizesVertically() }?.contentRight() ?: 0
            publish()
        }
    }

    /** Runs [task] with the runtime on the engine thread, then publishes a frame unless [publish] is false. */
    fun post(
        publish: Boolean = true,
        task: (SkinRuntime) -> Unit,
    ) {
        handler.post {
            val runtime = rt ?: return@post
            runCatching { task(runtime) }.onFailure { e -> host.log("engine: $e") }
            if (publish) publish()
        }
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
                tick()
            } finally {
                tickWaiting.set(false)
            }
        }
    }

    private fun tick() {
        val runtime = rt ?: return
        val t0 = System.nanoTime()
        val changed =
            runCatching { runtime.tick() }.getOrElse { e ->
                host.log("tick: $e")
                false
            }
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
            val runtime = rt ?: return@post
            val (mx, my) = moveWaiting.getAndSet(null) ?: return@post
            runCatching { runtime.pointerMove(mx, my) }.onFailure { e -> host.log("move: $e") }
            publish()
        }
    }

    fun pointerUp(
        x: Int,
        y: Int,
    ) = post {
        flushMove(it)
        it.pointerUp(x, y)
    }

    fun longPress(
        x: Int,
        y: Int,
    ) = post {
        flushMove(it)
        it.longPress(x, y)
    }

    /** Ends the engine thread once the work already posted is done; a menu waiting now closes first. */
    fun close() {
        host.menus.close()
        thread.quitSafely()
    }

    /** A move still waiting goes first, so a release lands where the finger last was. */
    private fun flushMove(runtime: SkinRuntime) {
        moveWaiting.getAndSet(null)?.let { (mx, my) -> runtime.pointerMove(mx, my) }
    }

    /**
     * Builds a new frame from the runtime and hands it to the screen; true when one was published.
     * [tickNanos] is the tick that changed it, for the frame's build time. A draw list that fails
     * keeps the last picture (or none) and shows the failure in the log.
     */
    private fun publish(tickNanos: Long = 0): Boolean {
        val runtime = rt ?: return false
        seenVersion = host.version
        val t0 = System.nanoTime()
        publishing = true
        val stack =
            try {
                runCatching { runtime.drawWindows() }.getOrElse { e ->
                    if (!drawFailing) {
                        Log.w(WalHost.TAG, "draw list", e)
                        host.log("draw: $e")
                    }
                    drawFailing = true
                    null
                }
            } finally {
                publishing = false
            }
        if (stack != null) drawFailing = false
        val layout = runtime.mainLayout
        published.set(
            WalFrame(
                stack = stack ?: published.get()?.stack.orEmpty(),
                mainSize = layout?.size,
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
}
