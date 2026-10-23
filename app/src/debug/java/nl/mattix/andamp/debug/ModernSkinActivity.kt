// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import nl.mattix.andamp.wal.WalEngine
import nl.mattix.andamp.wal.WalFiles
import nl.mattix.andamp.wal.WalFrame
import nl.mattix.andamp.wal.WalHost
import nl.mattix.andamp.wal.WalPrepare
import nl.mattix.andamp.wal.WalScale
import nl.mattix.andamp.wal.WalSettings
import nl.mattix.andamp.wal.drawWalStack
import nl.mattix.andamp.wal.drawWalWindow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proof of concept: shows the main window of a modern (.wal) skin and runs its scripts.
 *
 * Debug builds put it in the launcher as "Andamp Modern". It lists the .wal files in the app's
 * files directory and opens a picked one; "Open .wal…" copies a skin in from the phone's files.
 * An extra `wal` (a file name in that directory) opens one straight away:
 *
 * ```
 * adb shell am start -n nl.mattix.andamp/.debug.ModernSkinActivity --es wal mmd3.wal
 * ```
 */
class ModernSkinActivity : ComponentActivity() {
    private var current by mutableStateOf<File?>(null)
    private var skins by mutableStateOf<List<File>>(emptyList())

    private val pick =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .OpenDocument(),
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            val name =
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "skin.wal"
            val target =
                File(
                    filesDir,
                    name.replace(Regex("[^A-Za-z0-9._ -]"), "_").let { if (it.endsWith(".wal", true)) it else "$it.wal" },
                )
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            refresh()
            current = target
        }

    private fun refresh() {
        skins =
            filesDir.listFiles { f -> f.name.endsWith(".wal", ignoreCase = true) }?.sortedBy { it.name.lowercase() }
                ?: emptyList()
    }

    /** Saves the settings of the skin on screen, on its engine thread; set by the screen while it shows. */
    private var saveCurrent: (() -> CountDownLatch)? = null

    /** The last save asked for, which the next screen waits for before it restores. */
    private var lastSave: CountDownLatch? = null

    private fun openFrom(intent: android.content.Intent) {
        lastSave = saveCurrent?.invoke()
        check = Check.from(intent)
        probe = intent.getBooleanExtra("probe", false)
        intent.getStringExtra("wal")?.let { name ->
            File(filesDir, name).takeIf { it.exists() }?.let {
                current = it
                // a start for the skin already on screen still starts the run over
                launches++
            }
        }
    }

    private var check by mutableStateOf<Check?>(null)

    /** `--ez probe true`: times the skin on this phone (see [Probe]) instead of showing it for use. */
    private var probe by mutableStateOf(false)

    /** Starts with a skin named, so each one shows the skin anew. */
    private var launches by mutableIntStateOf(0)

    // a start with a skin named while the task already exists arrives here
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        openFrom(intent)
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (current != null) current = null else finish()
                }
            },
        )
        setContent {
            val file = current
            if (file == null) {
                SkinList(
                    skins,
                    onOpen = {
                        // a skin opened from the list is for use, whatever the start asked for
                        check = null
                        probe = false
                        current = it
                    },
                    onPick = { pick.launch(arrayOf("*/*")) },
                )
            } else {
                androidx.compose.runtime.key(
                    file,
                    check,
                    probe,
                    launches,
                ) {
                    ModernSkinScreen(file, check, if (probe) Probe(file.name) else null, lastSave) { saveCurrent = it }
                }
            }
        }
    }
}

/**
 * A scripted run for comparing with the Winamp references, started from adb:
 * `am start ... --es wal <file> --ez check true [--es taps id,id] [--es theme <name>] [--es view w,h] [--es playlist a|b]`.
 * It puts the skin in the reference state (the reference player, played 1 s and stopped), taps
 * objects by id, then renders every window at 1x with the app's renderer (drawWalWindow) into
 * files/check/window<n>.png and logs "check done". No screen coordinates, no screenshots.
 */
private data class Check(
    val taps: List<String>,
    val theme: String?,
    val view: Pair<Int, Int>?,
    val playlist: List<String>,
    val mainSize: Pair<Int, Int>? = null,
    val show: List<String> = emptyList(),
) {
    companion object {
        fun from(intent: android.content.Intent): Check? {
            if (!intent.getBooleanExtra("check", false)) return null
            val view =
                intent
                    .getStringExtra("view")
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.takeIf { it.size == 2 }
            return Check(
                intent
                    .getStringExtra("taps")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                intent.getStringExtra("theme"),
                view?.let { it[0] to it[1] },
                intent
                    .getStringExtra("playlist")
                    ?.split('|')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null }
                    ?: listOf("Andamp"),
                intent
                    .getStringExtra(
                        "mainsize",
                    )?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.takeIf { it.size == 2 }
                    ?.let {
                        it[0] to
                            it[1]
                    },
                intent
                    .getStringExtra("show")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
            )
        }
    }
}

/** 2026-10-20 12:00:00 UTC, the moment ReferenceRenderTest's clock starts at. */
private const val CHECK_CLOCK_START = 1_792_324_800_000L

/** The Winamp VM's work area, which ReferenceRenderTest uses as the viewport too. */
private const val CHECK_VIEWPORT_W = 1248

private const val CHECK_VIEWPORT_H = 853

/**
 * Renders each window of the stack at 1x with the app's renderer and writes files/check/window<n>.png,
 * and window<n>-notext.png without the TrueType text, so score.py can tell text pixels from the rest.
 */
private fun writeCheckImages(
    stack: List<Pair<nl.mattix.andamp.modernskin.skin.Rect, List<nl.mattix.andamp.modernskin.skin.DrawCmd>>>,
    host: WalHost,
    dir: File,
) {
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() }
    stack.forEachIndexed { i, (r, cmds) ->
        for (noText in listOf(false, true)) {
            val bmp = ImageBitmap(r.width, r.height)
            val canvas =
                androidx.compose.ui.graphics
                    .Canvas(bmp)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                androidx.compose.ui.unit
                    .Density(1f),
                androidx.compose.ui.unit.LayoutDirection.Ltr,
                canvas,
                Size(r.width.toFloat(), r.height.toFloat()),
            ) {
                drawWalWindow(cmds, host, withText = !noText)
            }
            val file = File(dir, if (noText) "window$i-notext.png" else "window$i.png")
            file.outputStream().use { bmp.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

@Composable
private fun SkinList(
    skins: List<File>,
    onOpen: (File) -> Unit,
    onPick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF202020))
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        BasicText("Modern skins (test)", style = TextStyle(color = Color.White, fontSize = 22.sp))
        Box(
            Modifier
                .padding(vertical = 12.dp)
                .background(Color(0xFF3A3A3A))
                .clickable(onClick = onPick)
                .padding(12.dp),
        ) {
            BasicText("Open .wal…", style = TextStyle(color = Color(0xFFB0FFB0), fontSize = 18.sp))
        }
        if (skins.isEmpty()) BasicText("No skins yet.", style = TextStyle(color = Color.Gray, fontSize = 16.sp))
        for (f in skins) {
            Box(Modifier.fillMaxWidth().clickable { onOpen(f) }.padding(vertical = 12.dp)) {
                BasicText(f.name.removeSuffix(".wal"), style = TextStyle(color = Color.White, fontSize = 18.sp))
            }
        }
    }
}

/**
 * Times a skin on the phone: opening its archive, loading (cold, then again warm with fresh
 * caches), and then a fixed run on the display clock once both loads are done: settle 2 s (the
 * first draws), stopped 10 s, playing 10 s, stopped 10 s. The engine thread records each tick's
 * time, the draw list's time when one was made, the tick's script steps and timer events; the main
 * thread records the time to draw each new frame, and that frame's whole cost (the engine's build
 * plus the drawing). Writes files/probe/<skin>.json and logs "probe done".
 */
private class Probe(
    val skin: String,
) {
    @Volatile var openMs = 0.0

    @Volatile var loadMs = 0.0

    @Volatile var loadSteps = 0L

    @Volatile var warmLoadMs = 0.0

    @Volatile var phase = "idle"

    private class Tick(
        val phase: String,
        val tickUs: Long,
        val drawWindowsUs: Long,
        val steps: Long,
        val timers: Long,
        val drew: Boolean,
    )

    private val ticks = ArrayList<Tick>()
    private val renderUs = HashMap<String, MutableList<Long>>()
    private val frameUs = HashMap<String, MutableList<Long>>()
    private val phaseMs = LinkedHashMap<String, Long>()
    private var lastTimers = -1L

    /** On the engine thread, after each tick. */
    @Synchronized
    fun ticked(
        rt: SkinRuntime,
        tickNanos: Long,
        drawNanos: Long,
        drew: Boolean,
        steps: Long,
    ) {
        val timers = if (lastTimers < 0) 0 else rt.timerFires - lastTimers
        lastTimers = rt.timerFires
        ticks += Tick(phase, tickNanos / 1000, drawNanos / 1000, steps, timers, drew)
    }

    /** On the main thread, after drawing a new frame that took [buildNanos] on the engine thread. */
    @Synchronized
    fun drew(
        renderNanos: Long,
        buildNanos: Long,
    ) {
        renderUs.getOrPut(phase) { ArrayList() } += renderNanos / 1000
        frameUs.getOrPut(phase) { ArrayList() } += (buildNanos + renderNanos) / 1000
    }

    @Synchronized
    fun ended(
        name: String,
        ms: Long,
    ) {
        phaseMs[name] = ms
    }

    private fun stats(v: List<Long>): org.json.JSONObject {
        val o = org.json.JSONObject().put("n", v.size)
        if (v.isEmpty()) return o
        val s = v.sorted()

        fun at(q: Double) = s[minOf(s.size - 1, (q * s.size).toInt())]
        return o
            .put("p50", at(0.5))
            .put("p95", at(0.95))
            .put("max", s.last())
            .put("mean", s.sum() / s.size)
    }

    @Synchronized
    fun json(): String {
        val phases = org.json.JSONObject()
        for ((ph, ms) in phaseMs) {
            val t = ticks.filter { it.phase == ph }
            val drawn = t.filter { it.drew }
            val timers = t.sumOf { it.timers }
            phases.put(
                ph,
                org.json
                    .JSONObject()
                    .put("ms", ms)
                    .put("tickUs", stats(t.map { it.tickUs }))
                    .put("drawWindowsUs", stats(drawn.map { it.drawWindowsUs }))
                    .put("engineUs", stats(t.map { it.tickUs + it.drawWindowsUs }))
                    .put("renderUs", stats(renderUs[ph].orEmpty()))
                    .put("frameUs", stats(frameUs[ph].orEmpty()))
                    .put("redraws", drawn.size)
                    .put("worstSteps", t.maxOfOrNull { it.steps } ?: 0)
                    .put("steps", t.sumOf { it.steps })
                    .put("timerFires", timers)
                    .put("timerPerSec", timers * 1000.0 / maxOf(1L, ms)),
            )
        }
        return org.json
            .JSONObject()
            .put("skin", skin)
            .put("openMs", openMs)
            .put("loadMs", loadMs)
            .put("loadSteps", loadSteps)
            .put("warmLoadMs", warmLoadMs)
            .put("phases", phases)
            .toString()
    }
}

/** Frames drawn during one slide or animation, written to logcat (tag ModernSkin) when it ends. */
private class SlideStats {
    var startedAt = 0L
    private var frames = 0
    private var drawNanos = 0L
    private var worstNanos = 0L

    fun frame(nanos: Long) {
        frames++
        drawNanos += nanos
        worstNanos = maxOf(worstNanos, nanos)
    }

    fun report() {
        val ms = (System.nanoTime() - startedAt) / 1_000_000
        if (frames > 0) {
            Log.i(
                WalHost.TAG,
                "slide: $frames frames in $ms ms (${frames * 1000 / maxOf(1, ms)} fps), " +
                    "draw avg ${drawNanos / frames / 1000} us, worst ${worstNanos / 1000} us",
            )
        }
        startedAt = 0L
        frames = 0
        drawNanos = 0L
        worstNanos = 0L
    }
}

/**
 * Makes the runtime for [file] and loads it, on the engine thread: what it kept last time, and in a
 * check the reference setup. A load that throws is the engine's start failure.
 */
@Suppress("LongParameterList") // the skin, its files, what plays, what it kept, the check and the probe
private fun loadRuntime(
    file: File,
    files: SkinFiles,
    host: WalHost,
    player: FakePlayer,
    settings: WalSettings?,
    check: Check?,
    checkClock: LongArray,
    probe: Probe?,
): SkinRuntime =
    SkinRuntime(files, host, player).also {
        if (check != null) {
            it.clock = { checkClock[0] }
            player.clock = { checkClock[0] }
            // the reference VM's screen: scripts that size a window from the viewport (Bento) then agree
            it.viewportW = CHECK_VIEWPORT_W
            it.viewportH = CHECK_VIEWPORT_H
        }
        prepare(it, file, check)
        settings?.restoreBeforeLoad(file.name, it)
        val loadStart = System.nanoTime()
        it.load()
        probe?.loadMs = (System.nanoTime() - loadStart) / 1e6
        probe?.loadSteps = it.scriptSteps
        // the color theme picked last time for this skin, as Winamp keeps it per skin
        settings?.restoreTheme(file.name, it)
        check?.theme?.let { t -> it.selectGammaSet(t) }
        check?.view?.let { v -> it.viewSize = v }
        // a scenario's size for a resizable main window, as ReferenceRenderTest gives it
        check?.mainSize?.let { (w, h) ->
            it.mainLayout?.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(w), Value.Int(h)))
        }
        // windows the skin opens by itself at start in Winamp, as ReferenceRenderTest opens them
        check?.show?.forEach { id -> it.container(id)?.let { c -> it.openWindow(c) } }
    }

/** The reference sequence, as ReferenceRenderTest runs it, on the check's stepped clock. */
private fun runCheck(
    rt: SkinRuntime,
    player: FakePlayer,
    check: Check,
    checkClock: LongArray,
) {
    fun step(ms: Long) {
        val until = checkClock[0] + ms
        while (checkClock[0] < until) {
            checkClock[0] = minOf(checkClock[0] + 16, until)
            rt.tick()
        }
    }
    step(3_000)
    player.play()
    step(1_000)
    player.stop()
    step(3_000)
    for (id in check.taps) {
        val target = rt.windows.asReversed().firstNotNullOfOrNull { it.findObject(id) }
        if (target == null) {
            rt.host.log("check: no object $id")
            continue
        }
        val origin = target.layoutObject?.let { rt.windowRect(it) }
        val cx = target.rect.left + target.rect.width / 2 + (origin?.left ?: 0)
        val cy = target.rect.top + target.rect.height / 2 + (origin?.top ?: 0)
        rt.pointerDown(cx, cy)
        rt.pointerUp(cx, cy)
        step(3_000)
    }
}

/**
 * The screen's size reaches the skin: a window the user can resize both ways (handles on its
 * edges, Bento) fills the screen, as maximized, its scale from its minimum size and its size from
 * what is left at that scale; the windows under the main one fill the height that is left.
 */
private fun fitToScreen(
    rt: SkinRuntime,
    px: IntSize,
    baseW: Int,
    check: Check?,
) {
    val layout = rt.mainLayout ?: return
    val bothWays = layout.resizesHorizontally() && layout.resizesVertically()
    val hasMinimum = layout.minW > 0 && layout.minH > 0
    if (check == null && hasMinimum && bothWays) {
        val fit = WalScale.of(px, layout.minW, layout.minH)
        val w = maxOf(layout.minW, (px.width / fit).toInt())
        val h = maxOf(layout.minH, (px.height / fit).toInt())
        if (layout.size != w to h) layout.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(w), Value.Int(h)))
    }
    val (lw, lh) = baseW.takeIf { it > 0 }?.let { it to layout.size.second } ?: layout.size
    val s = WalScale.of(px, lw, lh)
    // a check keeps the size it asked for (or the size the windows open at, as the references)
    if (check == null) rt.viewSize = (px.width / s).toInt() to (px.height / s).toInt()
}

// the debug screen: check mode, the probe, touches and drawing in one place
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ModernSkinScreen(
    file: File,
    check: Check? = null,
    probe: Probe? = null,
    /** The previous screen's save, which this skin waits for before it restores what it kept. */
    previousSave: CountDownLatch? = null,
    /** Hands the activity a way to save this skin's settings, or null when the screen goes. */
    onSaver: (((() -> CountDownLatch)?) -> Unit) = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // the default element set behind the skin: Andamp's, or Winamp's when files/wasabi-defaults.zip
    // is there (for comparing against Winamp only); a probe times what ships
    val base =
        remember {
            val defaults = File(file.parentFile, "wasabi-defaults.zip")
            if (defaults.exists() && probe == null) ZipSkinFiles(defaults) else AndampDefaultSet
        }
    // the other skins in the folder as siblings: Bento reads from "Big Bento"
    val siblings =
        remember {
            file.parentFile
                .listFiles { f -> f.name.endsWith(".wal", ignoreCase = true) }
                .orEmpty()
                .toList()
        }
    val files =
        remember {
            val openStart = System.nanoTime()
            WalFiles.open(file, siblings, base).also { probe?.openMs = (System.nanoTime() - openStart) / 1e6 }
        }
    // a check has nobody to pick from a menu: its menus answer 0 at once, as the JVM references do.
    // Outside a check the library and the visualizer are closed, as in the player
    val host =
        remember {
            val windowOpen = if (check == null) WalPrepare.windowOpen() else WalHost.LIBRARY_CLOSED
            WalHost(files, context.cacheDir, windowOpen).also { it.menus.unattended = check != null }
        }
    val player = remember { demoPlayer(check) }
    // what the skin kept from last time, by file name; a check or a probe starts clean
    val settings = remember { WalSettings(context).takeIf { check == null && probe == null } }
    // the check's stepped clock, as ReferenceRenderTest uses: the same frames every run, and no waiting.
    // Set before the skin loads, so the timers its scripts start on loading run on it too.
    val checkClock = remember { longArrayOf(CHECK_CLOCK_START) }
    var frameCount by remember { mutableIntStateOf(0) }
    // why the skin is not on: it did not start, or it stopped working
    var failure by remember { mutableStateOf<String?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val engine = remember { WalEngine(file.nameWithoutExtension, host) { main.post { frameCount++ } } }
    androidx.compose.runtime.DisposableEffect(engine) { onDispose { engine.close() } }
    LaunchedEffect(engine) {
        // the theme the skin wore when last kept, read and written on the engine thread only
        val keptTheme = arrayOfNulls<String>(1)
        engine.onTicked = { rt, tickNanos, drawNanos, drew, steps ->
            probe?.ticked(rt, tickNanos, drawNanos, drew, steps)
            // a theme the listener picked is kept at once, as Winamp keeps it per skin
            if (settings != null && rt.currentGammaSet != keptTheme[0]) {
                keptTheme[0] = rt.currentGammaSet
                settings.save(file.name, rt)
            }
        }
        engine.onBroken = { main.post { failure = "stopped working" } }
        // a check renders at 1x, whatever the screen; in use the skin must fit the screen
        val screen =
            context.resources.displayMetrics
                .let { IntSize(it.widthPixels, it.heightPixels) }
                .takeIf { check == null }
        engine.start(
            box = screen,
            onStart = { e ->
                if (e != null) {
                    // said at once, so a check run stops waiting
                    if (check != null) Log.e(WalHost.TAG, "check failed", e)
                    main.post { failure = "did not open: ${e.message ?: e}" }
                }
            },
        ) {
            previousSave?.await(1, TimeUnit.SECONDS)
            loadRuntime(file, files, host, player, settings, check, checkClock, probe).also { keptTheme[0] = it.currentGammaSet }
        }
        if (check != null) {
            engine.post { rt ->
                val stack =
                    runCatching {
                        runCheck(rt, player, check, checkClock)
                        rt.drawWindows()
                    }.getOrElse { e ->
                        // said at once, with the trace, so a check run stops waiting
                        Log.e(WalHost.TAG, "check failed", e)
                        return@post
                    }
                val windows = rt.windows.size
                main.post {
                    writeCheckImages(stack, host, File(context.filesDir, "check"))
                    Log.i(WalHost.TAG, "check done: $windows windows")
                }
            }
        }
    }
    // kept when the screen goes away, when the app leaves the screen, and before another start replaces it
    if (settings != null) {
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(engine, owner) {
            val save = {
                CountDownLatch(1).also { done ->
                    engine.post { rt ->
                        settings.save(file.name, rt)
                        done.countDown()
                    }
                }
            }
            val observer =
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                        // a menu left open would hold the engine, and the save behind it
                        engine.dismissMenu()
                        save()
                    }
                }
            owner.lifecycle.addObserver(observer)
            onSaver(save)
            onDispose {
                owner.lifecycle.removeObserver(observer)
                onSaver(null)
                save()
            }
        }
    }
    // the main thread asks for a tick each display frame while something slides or a probe runs,
    // else every 16 ms: timers and the clock need no more; a check steps the check clock instead
    if (check == null) {
        LaunchedEffect(engine) {
            while (true) {
                if (probe != null || engine.frame?.animating == true) withFrameNanos { } else delay(16)
                engine.requestTick()
            }
        }
    }
    if (probe != null) {
        LaunchedEffect(engine) {
            // the same skin loaded again on the engine thread, with fresh files and caches: the load
            // once the engine's code is warm. The phases start when both loads are done
            val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
            engine.post(publish = false) {
                val warmFiles = WalFiles.open(file, siblings, base)
                val again = SkinRuntime(warmFiles, WalHost(warmFiles, context.cacheDir, WalPrepare.windowOpen()), FakePlayer())
                prepare(again, file, check)
                val t0 = System.nanoTime()
                runCatching { again.load() }
                probe.warmLoadMs = (System.nanoTime() - t0) / 1e6
                loaded.complete(Unit)
            }
            loaded.await()

            suspend fun runPhase(
                name: String,
                ms: Long,
            ) {
                probe.phase = name
                val start = System.currentTimeMillis()
                delay(ms)
                probe.ended(name, System.currentTimeMillis() - start)
            }
            runPhase("settle", 2_000)
            runPhase("stopped", 10_000)
            engine.post { player.play() }
            runPhase("playing", 10_000)
            engine.post { player.stop() }
            runPhase("stoppedAgain", 10_000)
            val out = File(context.filesDir, "probe").apply { mkdirs() }
            File(out, file.nameWithoutExtension + ".json").writeText(probe.json())
            Log.i(WalHost.TAG, "probe done: ${file.name}")
        }
    }
    val slide = remember { SlideStats() }
    // where a fractional scale draws the stack whole before it is shrunk (see drawWalStack)
    val layer = rememberGraphicsLayer()
    // the frame the probe timed last, so a frame drawn again is not counted twice
    val probed = remember { arrayOfNulls<WalFrame>(1) }
    val shown = engine.frame.takeIf { frameCount >= 0 }
    // Back puts another window away before it leaves the skin
    androidx.activity.compose.BackHandler(enabled = shown?.windowOpen == true) { engine.post { it.closeTopWindow() } }
    Column(Modifier.fillMaxSize().background(Color(0xFF202020)).safeDrawingPadding()) {
        val mainSize = shown?.mainSize
        failure?.let { BasicText(it, style = TextStyle(color = Color(0xFFFF8080))) }
        if (shown == null) {
            if (failure == null) BasicText("loading", style = TextStyle(color = Color.White))
        } else if (mainSize == null) {
            BasicText("no main layout", style = TextStyle(color = Color.White))
        } else {
            // the size is posted when it changes; a lambda made per frame would post it every frame
            val baseW = shown.baseW
            val fit = remember(engine, baseW) { { px: IntSize -> engine.post { rt -> fitToScreen(rt, px, baseW, check) } } }
            // magenta behind the skin in a check, as on the reference Windows desktop, so transparency
            // shows; in use a dark gray
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(if (check != null) Color(0xFFFF00FF) else Color(0xFF202020))
                    .onSizeChanged(fit),
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(engine) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val f = engine.frame
                                val size2 = f?.mainSize ?: return@awaitEachGesture
                                val scale = WalScale.fit(size, size2, f.baseW)
                                // 24 dp beside a control still counts: every control answers across 48 dp
                                val slop = kotlin.math.ceil(24.dp.toPx() / scale).toInt()
                                engine.post(publish = false) { it.touchSlop = slop }
                                val downAt = System.currentTimeMillis()
                                var moved = false
                                val left = if (check == null) WalScale.left(size.width.toFloat(), f.contentRight, scale) else 0f

                                fun toSkin(o: Offset) = ((o.x - left) / scale).toInt() to (o.y / scale).toInt()
                                val (sx, sy) = toSkin(down.position)
                                engine.pointerDown(sx, sy)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.positionChanged()) {
                                        moved = true
                                        val (mx, my) = toSkin(change.position)
                                        engine.pointerMove(mx, my)
                                    }
                                    if (change.changedToUp()) {
                                        val (ux, uy) = toSkin(change.position)
                                        if (!moved && System.currentTimeMillis() - downAt > LONG_PRESS_MS) {
                                            engine.longPress(ux, uy)
                                        } else {
                                            engine.pointerUp(ux, uy)
                                        }
                                        break
                                    }
                                }
                            }
                        },
                ) {
                    frameCount // read so a new frame redraws
                    val f = engine.frame ?: return@Canvas
                    val size2 = f.mainSize ?: return@Canvas
                    val drawStart = System.nanoTime()
                    val scale = WalScale.fit(IntSize(size.width.toInt(), size.height.toInt()), size2, f.baseW)
                    // the stack centered across the screen, as the classic player places its windows
                    val left = if (check == null) WalScale.left(size.width, f.contentRight, scale) else 0f
                    drawWalStack(f.stack, host, scale, left, layer)
                    val drawn = System.nanoTime() - drawStart
                    if (f.animating && slide.startedAt == 0L) slide.startedAt = drawStart
                    if (slide.startedAt != 0L) slide.frame(drawn)
                    if (!f.animating && slide.startedAt != 0L) slide.report()
                    if (probe != null && f !== probed[0]) {
                        probed[0] = f
                        probe.drew(drawn, f.buildNanos)
                    }
                }
                engine.menu.takeIf { frameCount >= 0 }?.let { menu ->
                    SkinMenuOverlay(menu) { id -> engine.answerMenu(menu, id) }
                }
            }
        }
        BasicText(
            shown?.log?.joinToString("\n").orEmpty(),
            style = TextStyle(color = Color(0xFFB0FFB0), fontSize = 10.sp),
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
    }
}

/**
 * A skin's popup menu over the skin, while its script waits: a command answers its id; a tap
 * outside, or Back from the top level, answers 0. Submenus open in place, and Back goes up one.
 */
@Composable
private fun SkinMenuOverlay(
    menu: SkinMenu,
    onPick: (Int) -> Unit,
) {
    // the submenus opened, innermost last
    var path by remember(menu) { mutableStateOf(listOf(menu)) }
    androidx.activity.compose.BackHandler { if (path.size > 1) path = path.dropLast(1) else onPick(0) }
    val row = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .pointerInput(menu) { detectTapGestures { onPick(0) } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(Color(0xFF303030))
                // a tap between rows stays in the menu
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()),
        ) {
            for (item in path.last().items) {
                when (item) {
                    is SkinMenu.Command -> {
                        MenuRow(
                            if (item.checked) "\u2713" else "",
                            item.text,
                            if (item.disabled) Color.Gray else Color.White,
                            Modifier.clickable(enabled = !item.disabled) { onPick(item.id) }.then(row),
                        )
                    }

                    is SkinMenu.Submenu -> {
                        MenuRow("", "${item.text}  \u25B8", Color.White, Modifier.clickable { path = path + item.menu }.then(row))
                    }

                    SkinMenu.Separator -> {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Gray))
                    }
                }
            }
        }
    }
}

/** One row of [SkinMenuOverlay]: a check mark in a column of its width, then the words. */
@Composable
private fun MenuRow(
    mark: String,
    text: String,
    color: Color,
    modifier: Modifier,
) {
    androidx.compose.foundation.layout.Row(modifier) {
        BasicText(mark, style = TextStyle(color = color, fontSize = 16.sp), modifier = Modifier.widthIn(min = 24.dp))
        BasicText(text, style = TextStyle(color = color, fontSize = 16.sp))
    }
}

/** What plays in the preview: in a check the reference player's titles, else a few demo rows. */
private fun demoPlayer(check: Check?): FakePlayer =
    FakePlayer().apply {
        if (check != null) {
            // what the Winamp references play: the scenario's titles as 10 min silent files
            title = check.playlist.first()
            lengthMs = 600_000
            playlist =
                check.playlist.map {
                    nl.mattix.andamp.modernskin.skin
                        .Track(it, 600_000)
                }
            decoderName = "Nullsoft Waveform Decoder v3.25"
            // 8 kHz 8-bit mono: 64 kbps
            bitrateKbps = 64
            sampleRateKhz = 8
            channels = 1
        } else {
            // a few entries, so the playlist editor has rows to show and tap
            playlist =
                listOf(
                    "Andamp - Modern skin demo" to 215_000,
                    "Second Song" to 187_000,
                    "Third" to 242_000,
                    "Fourth Track" to 199_000,
                ).map { (t, ms) ->
                    nl.mattix.andamp.modernskin.skin
                        .Track(t, ms)
                }
        }
    }

/** How long a press must last to count as a long press (Winamp's right click). */
private const val LONG_PRESS_MS = 600

/**
 * What a runtime for [file] is given before it loads, the same for every load of it: in use what the
 * player gives a skin ([WalPrepare]), named by its file; in a check the reference VM's setup.
 */
private fun prepare(
    rt: SkinRuntime,
    file: File,
    check: Check?,
) {
    if (check == null) {
        WalPrepare.prepare(rt, file.nameWithoutExtension)
        return
    }
    // Winamp's getSkinName() is the skin's folder name: here the file's
    rt.skinName = file.nameWithoutExtension
    // the reference VM has Winamp's library inside the skin, and nothing outside it answers host actions
    rt.haveLibrary = true
}
