// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.IntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.sync.Mutex
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A worn modern skin, as the player screen sees it ([WalSurface]): its engine, its files and the
 * player its controls drive. SkinOps keeps one for the modern skin on screen, and one parked under
 * a music source's classic skin.
 *
 * Making one is cheap: nothing is read and no thread starts until the first [fit] from a player
 * surface, or [startAndAwait] for an import. The engine thread is named after [id]. Before the skin
 * loads it is readied as [WalPrepare] readies it, with what it kept last time ([WalSettings]); once
 * loaded it puts back the color theme it wore. The box of the first fit reaches it before its first
 * frame (see [WalEngine.fit]), and so does [awake] when it was set before. Each call the listener
 * makes on the skin sets [lastInputAt].
 *
 * What it keeps between runs is saved [SAVE_DELAY_MS] after a change, when [save] or [park] asks,
 * and when it is let go with a save. Where the listener moved its stack is kept as it is moved.
 *
 * A start that fails after a fit, and a skin that stops working, are told to [onFailed] on the main
 * thread.
 */
internal class WalLook(
    /** The skin's id in the library. */
    val id: String,
    private val file: File,
    /** The name the listener knows the skin by, which its scripts read; empty takes the name its skininfo gives. */
    private val name: String,
    /** The library's other modern skins it may read from, as [WalFiles.open] takes them. */
    private val siblings: Map<String, File>,
    private val cacheDir: File,
    /** Where it keeps its settings between runs; null keeps nothing. */
    private val settings: WalSettings?,
    /** The player its controls drive. */
    private val player: Player,
    /** Whether a window the app shows in place of the skin's is open; see [WalHost]. */
    private val windowOpen: (kind: String) -> Boolean? = WalPrepare.windowOpen(),
    /** What [wakeKey] gives. */
    private val wake: () -> Any = { Unit },
    /** The time it has to start; see [WalEngine.start]. */
    private val budgetMs: Long = WalBuild.START_BUDGET_MS,
    /** Runs a task on the main thread. */
    private val toMain: (Runnable) -> Unit = mainPoster(),
) : WalSurface {
    private val frameCount = mutableIntStateOf(0)

    override val frames: IntState get() = frameCount

    override var layout: WalLayout? by mutableStateOf(null)
        private set

    override var menu: SkinMenu? by mutableStateOf(null)
        private set

    override val turn = Mutex()

    override var sink: WalSink? = null

    /** Handed to the engine as it is set, and to an engine that starts later as it starts. */
    @Volatile
    override var awake = false
        set(value) {
            synchronized(startLock) {
                field = value
                engine?.awake = value
            }
        }

    override var lastInputAt by mutableLongStateOf(0L)
        private set

    /**
     * Told on the main thread when the skin did not start after a [fit] (false) or stopped working
     * (true). Not told after [release], nor for a failure [startAndAwait] reports itself.
     */
    var onFailed: ((stoppedWorking: Boolean) -> Unit)? = null

    /** The name the skin loaded under: [name], or its skininfo name when that is empty; empty before it loads. */
    @Volatile
    var loadedName = ""
        private set

    /** [release] was called. */
    @Volatile
    var released = false
        private set

    /** The engine once started, null before that and after [release]; tests run work on its thread. */
    @Volatile
    internal var engine: WalEngine? = null
        private set

    /** How often [trimDrawing] let go of drawing caches; for tests. */
    internal var trims = 0
        private set

    /** The engine's host once started, null before that and after [release]; tests open menus through it. */
    @Volatile
    internal var host: WalHost? = null
        private set

    /** Guards the start against a release on another thread. */
    private val startLock = Any()

    /** What the start reported: null when the skin is on, else why it is not. */
    private val started = CompletableFuture<Throwable?>()

    /** The start is [startAndAwait]'s, whose caller hears a failure in place of [onFailed]. */
    @Volatile
    private var awaited = false

    // the main thread's
    private var lastFit: WalFit? = null
    private var lastSeq = 0
    private var drawFailing = false

    /** A frame or menu change already waits for its main post, which takes the latest. */
    private val updateWaiting = AtomicBoolean(false)

    /** The settings version last saved or scheduled; the engine thread's. */
    private var keptVersion = -1

    /** Guards [savesClosed]: after a release that keeps nothing, no save lands. */
    private val saveLock = Any()
    private var savesClosed = false

    /** What the delayed save waits under on the engine thread. */
    private val saveToken = Any()

    /** Where the listener moved the stack, as [settings] kept it; after a release without a save, no place is kept either. */
    override val places =
        WalPlaces(settings?.place(id, sideways = false), settings?.place(id, sideways = true)) { sideways, move ->
            synchronized(saveLock) { if (!savesClosed) settings?.keepPlace(id, sideways, move) }
        }

    override fun draw(
        scope: DrawScope,
        layer: GraphicsLayer,
    ) {
        val engine = engine ?: return
        val frame = engine.frame ?: return
        val at = frame.layout ?: return
        val host = host ?: return
        scope.drawWalStack(frame.stack, host, at.scale, 0f, layer)
        // only the first frame drawn after failed ones is reported, not every frame
        if (drawFailing) {
            drawFailing = false
            engine.drawWorked()
        }
    }

    override fun drawFailed(error: Throwable) {
        drawFailing = true
        engine?.drawFailed(error)
    }

    override fun fit(fit: WalFit) {
        if (fit.widthPx <= 0 || fit.heightPx <= 0) return
        if (released || fit == lastFit) return
        lastFit = fit
        start(IntSize(fit.widthPx, fit.heightPx), fit)
    }

    override fun requestTick(cause: WalCause) {
        engine?.requestTick(cause)
    }

    override fun wakeKey(): Any = wake()

    override fun pointerDown(
        x: Int,
        y: Int,
    ): Int {
        lastInputAt = SystemClock.uptimeMillis()
        val seq = ++lastSeq
        engine?.pointerDown(x, y, seq)
        return seq
    }

    override fun pointerMove(
        x: Int,
        y: Int,
    ) {
        lastInputAt = SystemClock.uptimeMillis()
        engine?.pointerMove(x, y)
    }

    override fun pointerUp(
        x: Int,
        y: Int,
    ) {
        lastInputAt = SystemClock.uptimeMillis()
        engine?.pointerUp(x, y)
    }

    override fun pointerCancel() {
        lastInputAt = SystemClock.uptimeMillis()
        engine?.pointerCancel()
    }

    override fun doubleTap(
        x: Int,
        y: Int,
    ): Int {
        lastInputAt = SystemClock.uptimeMillis()
        val seq = ++lastSeq
        engine?.doubleTap(x, y, seq)
        return seq
    }

    override fun rightClick(seq: Int) {
        lastInputAt = SystemClock.uptimeMillis()
        engine?.rightClick(seq)
    }

    override fun closeTopWindow() {
        lastInputAt = SystemClock.uptimeMillis()
        engine?.post(cause = WalCause.TOUCH) { it.closeTopWindow() }
    }

    override fun answerMenu(
        menu: SkinMenu,
        id: Int,
    ) {
        lastInputAt = SystemClock.uptimeMillis()
        engine?.answerMenu(menu, id)
    }

    override fun dismissMenu() {
        engine?.dismissMenu()
    }

    override fun save() {
        engine?.post(publish = false, task = ::keep)
    }

    /**
     * Starts the skin with no box yet and waits for it to load, for an import: called off the main
     * thread, and waits at most the start's time. A menu its scripts open while it loads is answered
     * at once with nothing picked; once it is on, the player surface answers them. Returns
     * [loadedName]. Throws why it did not start: what its load threw, or a [TimeoutException].
     * [onFailed] is not told; the caller lets the look go.
     */
    fun startAndAwait(): String {
        check(!released) { "$id was let go" }
        awaited = true
        start(box = null, fit = null)
        val failure =
            try {
                started.get(budgetMs + AWAIT_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                TimeoutException("took longer than $budgetMs ms to start")
            }
        if (failure != null) throw failure as? Exception ?: IllegalStateException("did not start", failure)
        return loadedName
    }

    /** Put away under a music source's classic skin: a menu waiting closes, and what the skin keeps is saved. */
    fun park() {
        dismissMenu()
        save()
    }

    /**
     * Lets the skin go: a menu waiting closes, what it keeps is saved when [save] says so, and the
     * engine thread ends once the work before it is done. After a release without a save, no save
     * lands, so the skin's settings can be forgotten at once. Later calls do nothing. Called on the
     * main thread, or by an import whose skin did not start.
     */
    fun release(save: Boolean) {
        val stopping =
            synchronized(startLock) {
                if (released) return
                released = true
                engine.also {
                    engine = null
                    host = null
                }
            }
        // nothing more to draw; an import that let its look go calls this off the main thread
        val clear =
            Runnable {
                layout = null
                menu = null
            }
        if (Looper.myLooper() == Looper.getMainLooper()) clear.run() else toMain(clear)
        if (!save) synchronized(saveLock) { savesClosed = true }
        if (stopping == null) return
        stopping.cancel(saveToken)
        stopping.dismissMenu()
        if (save) stopping.post(publish = false, task = ::keep)
        stopping.close()
    }

    /**
     * Lets go of what was made for drawing the skin (see [WalHost.trimDrawing]), while it is off
     * screen. A look a player surface holds the [turn] of keeps it. On the main thread.
     */
    fun trimDrawing() {
        if (turn.isLocked) return
        val host = host ?: return
        host.trimDrawing()
        trims++
    }

    /**
     * Starts the engine, once, with [fit] as the first box it goes in and [box] its size for the
     * start's checks; after that, passes [fit] on. Nothing starts after [release].
     */
    private fun start(
        box: IntSize?,
        fit: WalFit?,
    ) {
        synchronized(startLock) {
            if (released) return
            engine?.let { running ->
                fit?.let(running::fit)
                return
            }
            val files = OpenedOnRead { WalFiles.open(file, siblings) }
            // an import has no player surface to show a menu its scripts open as the skin loads:
            // such a menu is answered at once with nothing picked
            val host = WalHost(files, cacheDir, windowOpen).apply { menus.unattended = awaited }
            val engine = WalEngine(id, host, onFrame = ::frameChanged)
            engine.onBroken = { toMain(Runnable { failed(stoppedWorking = true) }) }
            engine.onTask = ::keepLater
            // what the screen said before the engine started reaches it before the first fit
            engine.awake = awake
            this.host = host
            this.engine = engine
            fit?.let(engine::fit)
            engine.start(budgetMs, box, onStart = { failure -> startReported(failure, host) }) { build(files, host) }
        }
    }

    /** Makes the runtime and loads the skin, on the engine thread. */
    private fun build(
        files: SkinFiles,
        host: WalHost,
    ): SkinRuntime =
        SkinRuntime(files, host, player).apply {
            WalPrepare.prepare(this, name, ::hostAction)
            settings?.restoreBeforeLoad(id, this)
            load()
            settings?.restoreTheme(id, this)
            // putting back the theme it wore is no change to save
            keptVersion = settingsVersion
            loadedName = skinName
        }

    private fun startReported(
        failure: Throwable?,
        host: WalHost,
    ) {
        // from here on the player surface shows the skin's menus and answers them
        if (failure == null) host.menus.unattended = false
        started.complete(failure)
        if (failure != null && !awaited) toMain(Runnable { failed(stoppedWorking = false) })
    }

    /** Tells [onFailed], unless the look was let go meanwhile. On the main thread. */
    private fun failed(stoppedWorking: Boolean) {
        if (!released) onFailed?.invoke(stoppedWorking)
    }

    /** A button's host action, from the engine thread: to the sink on main, dropped while there is none. */
    private fun hostAction(
        action: String,
        param: String?,
    ) {
        toMain(Runnable { if (!released) sink?.hostAction(action, param) })
    }

    /** A new frame, or a menu opened or closed, on the engine thread: one main post takes them all. */
    private fun frameChanged() {
        if (updateWaiting.compareAndSet(false, true)) toMain(Runnable { update() })
    }

    /** Takes the engine's latest frame and menu into [layout], [menu] and [frames]. On the main thread. */
    private fun update() {
        updateWaiting.set(false)
        val engine = engine ?: return
        layout = engine.frame?.layout
        menu = engine.menu
        frameCount.intValue++
    }

    /** After each engine task: a change to what the skin keeps is saved once changes rest for [SAVE_DELAY_MS]. */
    private fun keepLater(rt: SkinRuntime) {
        if (rt.settingsVersion == keptVersion) return
        keptVersion = rt.settingsVersion
        engine?.postLater(saveToken, SAVE_DELAY_MS, ::keep)
    }

    /** Saves what the skin keeps between runs, unless a release without a save came first. On the engine thread. */
    private fun keep(rt: SkinRuntime) {
        val settings = settings ?: return
        val text = settings.snapshot(id, rt) ?: return
        synchronized(saveLock) { if (!savesClosed) settings.keep(id, text) }
    }

    /** A skin's files, opened by [open] at the first read: on the engine thread, as the skin loads. */
    private class OpenedOnRead(
        open: () -> SkinFiles,
    ) : SkinFiles {
        private val files by lazy(open)

        override fun read(path: String): ByteArray? = files.read(path)

        override fun exists(path: String): Boolean = files.exists(path)

        override fun names(): List<String> = files.names()
    }

    companion object {
        /** How long changes to what a skin keeps rest before they are saved. */
        const val SAVE_DELAY_MS = 1_000L

        /** How much longer than its start's time [startAndAwait] waits for the report, which may come from the main thread. */
        private const val AWAIT_GRACE_MS = 1_000L

        private fun mainPoster(): (Runnable) -> Unit {
            val main = Handler(Looper.getMainLooper())
            return { main.post(it) }
        }
    }
}
