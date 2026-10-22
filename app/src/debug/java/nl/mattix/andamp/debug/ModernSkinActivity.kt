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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
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
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import nl.mattix.andamp.wal.WalFiles
import nl.mattix.andamp.wal.WalHost
import nl.mattix.andamp.wal.WalScale
import nl.mattix.andamp.wal.WalSettings
import nl.mattix.andamp.wal.drawWalStack
import nl.mattix.andamp.wal.drawWalWindow
import java.io.File

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

    /** Saves the settings of the skin on screen; set by the screen while it shows. */
    private var saveCurrent: (() -> Unit)? = null

    private fun openFrom(intent: android.content.Intent) {
        saveCurrent?.invoke()
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
                ) { ModernSkinScreen(file, check, if (probe) Probe(file.name) else null) { saveCurrent = it } }
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
    rt: SkinRuntime,
    host: WalHost,
    dir: File,
) {
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() }
    rt.drawWindows().forEachIndexed { i, (r, cmds) ->
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
 * Times a skin on the phone: opening its archive, loading (cold,
 * then again warm with fresh caches), and then each frame of a fixed run on the display clock:
 * settle 2 s (the first draws after the warm load), stopped 10 s, playing 10 s, stopped 10 s. Per
 * frame it keeps the tick, drawWindows and drawing times, their sum, script steps and timer
 * events. Writes files/probe/<skin>.json and logs "probe done".
 */
private class Probe(
    val skin: String,
) {
    var openMs = 0.0
    var loadMs = 0.0
    var loadSteps = 0L
    var warmLoadMs = 0.0
    var phase = "idle"

    /** The last tick's time, added to the draw that follows it. */
    var lastTickUs = 0L

    class Frame(
        val phase: String,
        val tickUs: Long,
        val steps: Long,
        val timers: Long,
        val changed: Boolean,
    )

    val frames = ArrayList<Frame>()
    private val drawWindowsUs = HashMap<String, MutableList<Long>>()
    private val renderUs = HashMap<String, MutableList<Long>>()
    private val frameUs = HashMap<String, MutableList<Long>>()
    val phaseMs = LinkedHashMap<String, Long>()

    fun drew(
        windowsNanos: Long,
        renderNanos: Long,
    ) {
        drawWindowsUs.getOrPut(phase) { ArrayList() } += windowsNanos / 1000
        renderUs.getOrPut(phase) { ArrayList() } += renderNanos / 1000
        frameUs.getOrPut(phase) { ArrayList() } += lastTickUs + (windowsNanos + renderNanos) / 1000
        lastTickUs = 0
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

    fun json(): String {
        val phases = org.json.JSONObject()
        for ((ph, ms) in phaseMs) {
            val f = frames.filter { it.phase == ph }
            val timers = f.sumOf { it.timers }
            phases.put(
                ph,
                org.json
                    .JSONObject()
                    .put("ms", ms)
                    .put("tickUs", stats(f.map { it.tickUs }))
                    .put("drawWindowsUs", stats(drawWindowsUs[ph].orEmpty()))
                    .put("renderUs", stats(renderUs[ph].orEmpty()))
                    .put("frameUs", stats(frameUs[ph].orEmpty()))
                    .put("redraws", f.count { it.changed })
                    .put("worstSteps", f.maxOfOrNull { it.steps } ?: 0)
                    .put("steps", f.sumOf { it.steps })
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

    fun mark(name: String) {
        phase = name
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
                "ModernSkin",
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

// the debug screen: check mode, taps, windows and drawing in one place
@Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
@Composable
private fun ModernSkinScreen(
    file: File,
    check: Check? = null,
    probe: Probe? = null,
    /** Hands the activity a way to save this skin's settings, or null when the screen goes. */
    onSaver: (((() -> Unit)?) -> Unit) = {},
) {
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val host = remember { WalHost(files, context.cacheDir) }
    val player =
        remember {
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
        }
    // what the skin kept from last time, by file name; a check or a probe starts clean
    val settings = remember { WalSettings(context).takeIf { check == null && probe == null } }
    // the check's stepped clock, as ReferenceRenderTest uses: the same frames every run, and no waiting.
    // Set before the skin loads, so the timers its scripts start on loading run on it too.
    val checkClock = remember { longArrayOf(CHECK_CLOCK_START) }
    val rt =
        remember {
            SkinRuntime(files, host, player).also {
                if (check != null) {
                    it.clock = { checkClock[0] }
                    player.clock = { checkClock[0] }
                    // the reference VM's screen: scripts that size a window from the viewport (Bento) then agree
                    it.viewportW = CHECK_VIEWPORT_W
                    it.viewportH = CHECK_VIEWPORT_H
                }
                settings?.restoreBeforeLoad(file.name, it)
                prepare(it, file, check)
                val loadStart = System.nanoTime()
                runCatching { it.load() }.onFailure { e -> host.log("load failed: $e") }
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
        }
    var frame by remember { mutableIntStateOf(0) }
    // the main window's visible width at the start sets the scale (MMD3 keeps room on its right
    // for a panel that slides out); fixed, so the size does not jump when that panel opens
    // (a window the user resizes, Bento, is sized to the screen instead and scales from its own size)
    val baseW =
        remember(rt) {
            rt.mainLayout?.takeUnless { it.resizesHorizontally() && it.resizesVertically() }?.contentRight()
                ?: 0
        }
    var logText by remember { mutableStateOf("") }
    var savedTheme by remember { mutableStateOf(rt.currentGammaSet) }
    // kept when the screen goes away or the app leaves the screen
    if (settings != null) {
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(rt, owner) {
            val save = { settings.save(file.name, rt) }
            val observer =
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) save()
                }
            owner.lifecycle.addObserver(observer)
            // a start that replaces this screen saves first, so the next screen restores this one's state
            onSaver(save)
            onDispose {
                owner.lifecycle.removeObserver(observer)
                onSaver(null)
                save()
            }
        }
    }
    val slide = remember { SlideStats() }
    // Back puts another window away before it leaves the skin
    var frontOpen by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = frontOpen) {
        rt.closeTopWindow()
        frontOpen = false
        frame++
    }
    if (check != null) {
        // the reference sequence, as ReferenceRenderTest runs it
        LaunchedEffect(check) {
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
                    host.log("check: no object $id")
                    continue
                }
                val origin = target.layoutObject?.let { rt.windowRect(it) }
                val cx = target.rect.left + target.rect.width / 2 + (origin?.left ?: 0)
                val cy = target.rect.top + target.rect.height / 2 + (origin?.top ?: 0)
                rt.pointerDown(cx, cy)
                rt.pointerUp(cx, cy)
                step(3_000)
            }
            writeCheckImages(rt, host, File(context.filesDir, "check"))
            Log.i("ModernSkin", "check done: ${rt.windows.size} windows")
        }
    }
    if (probe != null) {
        LaunchedEffect(probe) {
            // the same skin loaded again in this process, with fresh caches: the load once the engine's code is warm
            run {
                val warmFiles = WalFiles.open(file, siblings, base)
                val again = SkinRuntime(warmFiles, WalHost(warmFiles, context.cacheDir), FakePlayer())
                prepare(again, file, check)
                val t0 = System.nanoTime()
                runCatching { again.load() }
                probe.warmLoadMs = (System.nanoTime() - t0) / 1e6
            }
            var lastTimers = rt.timerFires
            var seen = host.version

            suspend fun runPhase(
                name: String,
                ms: Long,
            ) {
                probe.mark(name)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < ms) {
                    withFrameNanos { }
                    val t0 = System.nanoTime()
                    val changed = runCatching { rt.tick() }.getOrDefault(false)
                    val tickUs = (System.nanoTime() - t0) / 1000
                    probe.lastTickUs = tickUs
                    val timers = rt.timerFires - lastTimers
                    lastTimers = rt.timerFires
                    probe.frames += Probe.Frame(name, tickUs, rt.scriptSteps, timers, changed)
                    if (changed || host.version != seen) {
                        seen = host.version
                        frame++
                    }
                }
                probe.phaseMs[name] = System.currentTimeMillis() - start
            }
            runPhase("settle", 2_000)
            runPhase("stopped", 10_000)
            player.play()
            runPhase("playing", 10_000)
            player.stop()
            runPhase("stoppedAgain", 10_000)
            val out = File(context.filesDir, "probe").apply { mkdirs() }
            File(out, file.nameWithoutExtension + ".json").writeText(probe.json())
            Log.i("ModernSkin", "probe done: ${file.name}")
        }
    }
    LaunchedEffect(Unit) {
        var seen = -1
        while (true) {
            if (rt.animating && slide.startedAt == 0L) slide.startedAt = System.nanoTime()
            if (!rt.animating && slide.startedAt != 0L) slide.report()
            // during a check only its stepped loop ticks, so tickers advance the same number of times as in the JVM test
            val changed =
                if (check != null || probe != null) {
                    false
                } else {
                    runCatching { rt.tick() }.getOrElse { e ->
                        host.log("tick: $e")
                        false
                    }
                }
            frontOpen = rt.topWindow != null
            // a theme the listener picked is kept at once, as Winamp keeps it per skin
            if (settings != null && rt.currentGammaSet != savedTheme) {
                savedTheme = rt.currentGammaSet
                settings.save(file.name, rt)
            }
            if (changed || host.version != seen) {
                seen = host.version
                frame++
                logText = host.recentLog.joinToString("\n")
            }
            // every display frame while something slides or plays; timers and the clock need no more than 60 Hz
            if (rt.animating) withFrameNanos { } else delay(16)
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF202020)).safeDrawingPadding()) {
        val layout = rt.mainLayout
        if (layout == null) {
            BasicText("no main layout", style = TextStyle(color = Color.White))
        } else {
            // the main window's width sets the scale; the windows under it fill the height that is left
            val (lw, lh) = layout.size
            // magenta behind the skin in a check, as on the reference Windows desktop, so transparency
            // shows; in use the app's own dark gray, which also hides seams between region edges at
            // fractional scales
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(if (check != null) Color(0xFFFF00FF) else Color(0xFF202020))
                    .onSizeChanged { px ->
                        // a window the user can resize both ways (handles on its edges, Bento) fills the
                        // screen, as maximized: the scale comes from its minimum size, its size from what
                        // is left at that scale
                        if (check == null && layout.minW > 0 && layout.minH > 0 && layout.resizesHorizontally() &&
                            layout.resizesVertically()
                        ) {
                            val fit = WalScale.of(px, layout.minW, layout.minH)
                            val w = maxOf(layout.minW, (px.width / fit).toInt())
                            val h = maxOf(layout.minH, (px.height / fit).toInt())
                            if (layout.size != w to h) {
                                layout.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(w), Value.Int(h)))
                            }
                        }
                        val (lw, lh) = baseW.takeIf { it > 0 }?.let { it to layout.size.second } ?: layout.size
                        val s = WalScale.of(px, lw, lh)
                        // a check keeps the size it asked for (or the windows' own, as the references)
                        if (check == null) rt.viewSize = (px.width / s).toInt() to (px.height / s).toInt()
                        frame++
                    },
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(rt) {
                            awaitEachGesture {
                                val scale = WalScale.fit(size, layout, baseW)
                                // 24 dp beside a control still counts: every control answers across 48 dp
                                rt.touchSlop = kotlin.math.ceil(24.dp.toPx() / scale).toInt()
                                val down = awaitFirstDown()
                                val downAt = System.currentTimeMillis()
                                var moved = false
                                val left =
                                    if (check ==
                                        null
                                    ) {
                                        WalScale.left(size.width.toFloat(), layout.contentRight(), scale)
                                    } else {
                                        0f
                                    }

                                fun toSkin(o: Offset) = ((o.x - left) / scale).toInt() to (o.y / scale).toInt()
                                val (sx, sy) = toSkin(down.position)
                                rt.pointerDown(sx, sy)
                                frame++
                                var longFired = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.positionChanged()) {
                                        moved = true
                                        val (mx, my) = toSkin(change.position)
                                        rt.pointerMove(mx, my)
                                        frame++
                                    }
                                    if (change.changedToUp()) {
                                        val (ux, uy) = toSkin(change.position)
                                        if (!moved && !longFired && System.currentTimeMillis() - downAt > 600) {
                                            longFired = true
                                            rt.longPress(ux, uy)
                                        } else {
                                            rt.pointerUp(ux, uy)
                                        }
                                        frame++
                                        break
                                    }
                                }
                            }
                        },
                ) {
                    frame // read so a new frame redraws
                    val drawStart = System.nanoTime()
                    val scale = WalScale.fit(IntSize(size.width.toInt(), size.height.toInt()), layout, baseW)
                    val stack =
                        runCatching { rt.drawWindows() }.getOrElse { e ->
                            host.log("draw: $e")
                            emptyList()
                        }
                    val windowsDone = System.nanoTime()
                    // the stack centered across the screen, as the classic player places its windows
                    val left = if (check == null) WalScale.left(size.width, layout.contentRight(), scale) else 0f
                    drawWalStack(stack, host, scale, left)
                    if (slide.startedAt != 0L) slide.frame(System.nanoTime() - drawStart)
                    probe?.drew(windowsDone - drawStart, System.nanoTime() - windowsDone)
                }
            }
        }
        BasicText(
            logText,
            style = TextStyle(color = Color(0xFFB0FFB0), fontSize = 10.sp),
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
    }
}

/** What a runtime for [file] is given before it loads, the same for every load of it. */
private fun prepare(
    rt: SkinRuntime,
    file: File,
    check: Check?,
) {
    // Winamp's getSkinName() is the skin's folder name: here the file's
    rt.skinName = file.nameWithoutExtension
    // the reference VM has Winamp's library inside the skin; the phone's library is a separate screen
    rt.haveLibrary = check != null
    // Winamp Modern ships its drawer animation off; start with it on, unless the skin kept a choice
    rt.storedSettings.putIfAbsent("Animate Config Drawer", "1")
    if (check == null) {
        // Bento opens the component it remembers, "Media Library" at first; Andamp's library is
        // a separate screen, so start Bento on its video tab instead (its playlist tab would
        // pull the playlist out of the top-right pane and collapse that pane)
        rt.privateStrings.putIfAbsent("${file.nameWithoutExtension.lowercase()}/component", "Video")
    }
}
