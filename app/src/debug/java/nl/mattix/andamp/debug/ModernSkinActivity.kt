// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.debug

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.Align
import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Gamma
import nl.mattix.andamp.modernskin.skin.GdiLineMetrics
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.VAlign
import nl.mattix.andamp.modernskin.skin.WithGeneratedImages
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
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

    private fun openFrom(intent: android.content.Intent) {
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
                ) { ModernSkinScreen(file, check, if (probe) Probe(file.name) else null) }
            }
        }
    }
}

/**
 * A scripted run for comparing with the Winamp references, started from adb:
 * `am start ... --es wal <file> --ez check true [--es taps id,id] [--es theme <name>] [--es view w,h] [--es playlist a|b]`.
 * It puts the skin in the reference state (the reference player, played 1 s and stopped), taps
 * objects by id, then renders every window at 1x through this screen's own drawing code into
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

/** Windows fonts skins name without a file, and the free font in Andamp's default set that stands in. */
private val STAND_INS = mapOf("tahoma" to "DejaVuSansCondensed")

/** The Winamp VM's work area, which ReferenceRenderTest uses as the viewport too. */
private const val CHECK_VIEWPORT_W = 1248

/** Window shape masks kept at once. */
private const val MAX_MASKS = 16

/** Stretched pictures kept at once (a resized window makes new sizes). */
private const val MAX_STRETCHED = 512
private const val CHECK_VIEWPORT_H = 853

/**
 * Renders each window of the stack at 1x with [drawCmd] and writes files/check/window<n>.png, and
 * window<n>-notext.png without the TrueType text, so score.py can tell text pixels from the rest.
 */
private fun writeCheckImages(
    rt: SkinRuntime,
    host: AndroidSkinHost,
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
                for (cmd in cmds) if (!(noText && cmd is DrawCmd.Text)) drawCmd(cmd, host)
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

/** The app's side of the skin: Android decodes images and measures text. */
private class AndroidSkinHost(
    private val files: SkinFiles,
    private val cacheDir: File,
) : SkinHost {
    val log = ArrayDeque<String>()

    val windows = ArrayDeque<DrawCmd.WindowStart>()

    /** Window shapes rendered once as masks, by what they are made of: a shape changes rarely, frames often. */
    private val shapeMasks =
        object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_MASKS
        }

    fun shapeMask(
        region: Region,
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    ): ImageBitmap? {
        val b = region.bounds() ?: return null
        if (b.right <= 0 || b.bottom <= 0) return null
        return shapeMasks.getOrPut(regionKey(region)) {
            val img = ImageBitmap(b.right, b.bottom)
            val size = Size(b.right.toFloat(), b.bottom.toFloat())
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                scope,
                scope.layoutDirection,
                androidx.compose.ui.graphics
                    .Canvas(img),
                size,
            ) {
                val whole =
                    androidx.compose.ui.geometry
                        .Rect(Offset.Zero, size)
                val canvasScope = this
                region.eval(
                    object : Region.Canvas {
                        override fun picture(
                            image: DrawCmd.Image,
                            hard: Boolean,
                        ) = canvasScope.drawImageCmd(image, this@AndroidSkinHost, mask = hard)

                        override fun box(rect: nl.mattix.andamp.modernskin.skin.Rect) =
                            canvasScope.drawRect(
                                Color.Black,
                                Offset(rect.left.toFloat(), rect.top.toFloat()),
                                Size(rect.width.toFloat(), rect.height.toFloat()),
                            )

                        override fun layer(
                            blend: Region.Blend,
                            body: () -> Unit,
                        ) {
                            val mode =
                                when (blend) {
                                    Region.Blend.ADD -> androidx.compose.ui.graphics.BlendMode.SrcOver
                                    Region.Blend.KEEP -> androidx.compose.ui.graphics.BlendMode.DstIn
                                    Region.Blend.CUT -> androidx.compose.ui.graphics.BlendMode.DstOut
                                }
                            canvasScope.drawContext.canvas.saveLayer(
                                whole,
                                androidx.compose.ui.graphics.Paint().apply {
                                    blendMode =
                                        mode
                                },
                            )
                            body()
                            canvasScope.drawContext.canvas.restore()
                        }
                    },
                )
            }
            img
        }
    }

    /** What a region is made of, as a cache key: pictures with where they sit, boxes, and the operations. */
    private fun regionKey(r: Region): String =
        when (r) {
            is Region.Pic -> {
                with(r.image) {
                    "P(${bitmap.file}:${bitmap.x},${bitmap.y},${bitmap.w},${bitmap.h}" +
                        "@${dst.left},${dst.top},${dst.width},${dst.height})"
                }
            }

            is Region.Box -> {
                "B(${r.rect.left},${r.rect.top},${r.rect.width},${r.rect.height})"
            }

            is Region.Op -> {
                "O(${r.base?.let(
                    ::regionKey,
                )}|${r.add.joinToString(
                    ",",
                    transform = ::regionKey,
                )}|${r.keep.joinToString(",", transform = ::regionKey)}|${r.cut.joinToString(",", transform = ::regionKey)})"
            }
        }

    var version = 0
    private val sizes = HashMap<String, Pair<Int, Int>?>()
    private val images = HashMap<String, ImageBitmap?>()
    private val typefaces = HashMap<String, Typeface?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun imageSize(file: String): Pair<Int, Int>? =
        sizes.getOrPut(file.lowercase()) {
            val bytes = files.read(file) ?: return@getOrPut null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val tooLarge = opts.outWidth.toLong() * opts.outHeight > nl.mattix.andamp.modernskin.skin.ImageLimits.MAX_PIXELS
            if (opts.outWidth > 0 && !tooLarge) opts.outWidth to opts.outHeight else null
        }

    fun image(file: String): ImageBitmap? =
        images.getOrPut(file.lowercase()) {
            val bytes = files.read(file) ?: return@getOrPut null
            // a picture claiming more pixels than a skin can need is not decoded
            if (nl.mattix.andamp.modernskin.skin.ImageLimits
                    .tooLarge(bytes)
            ) {
                return@getOrPut null
            }
            val opts = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }

    private val tinted = HashMap<Pair<String, Gamma>, ImageBitmap?>()
    private val masks = HashMap<String, ImageBitmap?>()

    /** A region picture as a mask: opaque where it cuts, clear elsewhere. */
    fun cutMask(file: String): ImageBitmap? =
        masks.getOrPut(file.lowercase()) {
            val src = image(file)?.asAndroidBitmap() ?: return@getOrPut null
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            for (i in px.indices) px[i] = if (DrawCmd.isCutPixel(px[i])) 0xFF000000.toInt() else 0
            android.graphics.Bitmap
                .createBitmap(
                    px,
                    src.width,
                    src.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                ).asImageBitmap()
        }

    /** The image with a color theme applied, made once per file and tint. */
    private val pieces = HashMap<String, ImageBitmap>()

    /** The part of [sheet] at x, y, w, h cut out as a separate picture, made once. */
    @Suppress("ComplexCondition")
    fun piece(
        sheet: ImageBitmap,
        sheetKey: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): ImageBitmap {
        if (x == 0 && y == 0 && w == sheet.width && h == sheet.height) return sheet
        return pieces.getOrPut("$sheetKey|$x,$y,$w,$h") {
            android.graphics.Bitmap
                .createBitmap(sheet.asAndroidBitmap(), x, y, w, h)
                .asImageBitmap()
        }
    }

    private val stretchedPieces =
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_STRETCHED
        }

    /** [piece] stretched to w x h with Winamp's floor mapping, made once per size. */
    fun stretched(
        piece: ImageBitmap,
        pieceKey: String,
        w: Int,
        h: Int,
    ): ImageBitmap =
        stretchedPieces.getOrPut("$pieceKey|${w}x$h") {
            val src = piece.asAndroidBitmap()
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            val out = IntArray(w * h)
            for (y in 0 until h) {
                val sy = (y.toLong() * src.height / h).toInt()
                for (x in 0 until w) out[y * w + x] = px[sy * src.width + (x.toLong() * src.width / w).toInt()]
            }
            android.graphics.Bitmap
                .createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }

    fun image(
        file: String,
        gamma: Gamma?,
    ): ImageBitmap? {
        if (gamma == null) return image(file)
        return tinted.getOrPut(file.lowercase() to gamma) {
            val src = image(file)?.asAndroidBitmap() ?: return@getOrPut null
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            for (i in px.indices) px[i] = gamma.apply(px[i])
            android.graphics.Bitmap
                .createBitmap(
                    px,
                    src.width,
                    src.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                ).asImageBitmap()
        }
    }

    /**
     * A font a skin names without a file is a Windows system font; the app carries free stand-ins
     * whose widths match closely (DejaVu Sans Condensed for Tahoma: Bento's tabs are sized by
     * the measured text). Bold has its own file there, so no fake bold.
     */
    fun standIn(
        font: FontDef?,
        bold: Boolean,
    ): Typeface? {
        val base = STAND_INS[font?.takeIf { it.file.isEmpty() }?.id ?: return null] ?: return null
        val path = "fonts/$base${if (bold) "-Bold" else ""}.ttf"
        return typefaces.getOrPut(path) {
            val bytes = AndampDefaultSet.read(path) ?: return@getOrPut null
            val f = File(cacheDir, "standin-$base${if (bold) "-Bold" else ""}.ttf")
            if (!f.exists()) f.writeBytes(bytes)
            runCatching { Typeface.createFromFile(f) }.getOrNull()
        }
    }

    fun typeface(font: FontDef?): Typeface? {
        if (font == null) return null
        return typefaces.getOrPut(font.id) {
            val bytes = files.read(font.file) ?: return@getOrPut null
            val f = File(cacheDir, "font-${font.id.replace(Regex("[^a-z0-9]"), "_")}.ttf")
            if (!f.exists()) f.writeBytes(bytes)
            runCatching { Typeface.createFromFile(f) }.getOrNull()
        }
    }

    private val gdiLines = HashMap<String, GdiLineMetrics?>()

    /** Ascent and descent in whole pixels as Windows lays the font out; null when its file gives none. */
    fun gdiLine(
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Pair<Int, Int>? {
        val f = font ?: return null
        val standIn = STAND_INS[f.takeIf { it.file.isEmpty() }?.id]?.let { "fonts/$it${if (bold) "-Bold" else ""}.ttf" }
        val key = standIn ?: f.id
        val m =
            gdiLines.getOrPut(key) {
                val bytes =
                    if (standIn !=
                        null
                    ) {
                        AndampDefaultSet.read(standIn)
                    } else {
                        files.read(f.file)
                    }
                bytes?.let { GdiLineMetrics.read(it) }
            }
        return m?.at(
            nl.mattix.andamp.modernskin.skin.Text
                .emSize(size),
        )
    }

    fun paintFor(
        font: FontDef?,
        size: Int,
        bold: Boolean = false,
    ): Paint {
        val standIn = standIn(font, bold)
        paint.typeface = standIn ?: typeface(font) ?: Typeface.DEFAULT
        paint.isFakeBoldText = bold && standIn == null
        paint.textSize =
            nl.mattix.andamp.modernskin.skin.Text
                .emSize(size)
        return paint
    }

    override fun textWidth(
        text: String,
        font: FontDef?,
        size: Int,
    ): Int = textWidth(text, font, size, false)

    override fun textWidth(
        text: String,
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Int = paintFor(font, size, bold).measureText(text).toInt()

    override fun log(message: String) {
        Log.i("ModernSkin", message)
        log.addLast(message)
        while (log.size > 12) log.removeFirst()
        version++
    }

    // the library is Andamp's own screen, never open in a skin; every other window (the playlist
    // included) is the skin's, open when its docked window shows
    override fun windowOpen(kind: String): Boolean? = if (kind == "ml") false else null

    override fun pixel(
        file: String,
        x: Int,
        y: Int,
    ): Int? = image(file)?.asAndroidBitmap()?.takeIf { x < it.width && y < it.height }?.getPixel(x, y)

    override fun invalidate() {
        version++
    }
}

/**
 * Times a skin on the phone: opening its archive and the other skins beside it, loading (cold,
 * then again warm with fresh caches), and then each frame of a fixed run on the display clock:
 * settle 2 s (the first draws after the warm load), stopped 10 s, playing 10 s, stopped 10 s. Per
 * frame it keeps the tick, drawWindows and drawing times, their sum, script steps and timer
 * events. Writes files/probe/<skin>.json and logs "probe done".
 */
private class Probe(
    val skin: String,
) {
    var openMs = 0.0
    var siblingsOpenMs = 0.0
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
            .put("siblingsOpenMs", siblingsOpenMs)
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
) {
    val files =
        remember {
            // the default element set behind the skin: Andamp's own, or Winamp's when
            // files/wasabi-defaults.zip is there (for comparing against Winamp only)
            val defaults = File(file.parentFile, "wasabi-defaults.zip")
            // the other skins in the folder as siblings: Bento reads from "Big Bento"
            val siblingsStart = System.nanoTime()
            val siblings =
                file.parentFile
                    .listFiles { f -> f.name.endsWith(".wal", ignoreCase = true) && f != file }
                    .orEmpty()
                    .associate { f -> f.nameWithoutExtension.replace(" ", "").lowercase() to ZipSkinFiles(f) }
            probe?.siblingsOpenMs = (System.nanoTime() - siblingsStart) / 1e6
            val openStart = System.nanoTime()
            val zip = ZipSkinFiles(file)
            probe?.openMs = (System.nanoTime() - openStart) / 1e6
            val skin =
                nl.mattix.andamp.modernskin.skin
                    .SkinsFolderFiles(zip, siblings)
            // a probe times what ships: Andamp's default set
            val base = if (defaults.exists() && probe == null) ZipSkinFiles(defaults) else AndampDefaultSet
            nl.mattix.andamp.modernskin.skin
                .LayeredSkinFiles(skin, base)
        }
    val context = androidx.compose.ui.platform.LocalContext.current
    val host = remember { AndroidSkinHost(WithGeneratedImages(files), context.cacheDir) }
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
    val prefs = remember { context.getSharedPreferences("modernskin", android.content.Context.MODE_PRIVATE) }
    val themeKey = "theme:${file.name}"
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
                prepare(it, file, check)
                val loadStart = System.nanoTime()
                runCatching { it.load() }.onFailure { e -> host.log("load failed: $e") }
                probe?.loadMs = (System.nanoTime() - loadStart) / 1e6
                probe?.loadSteps = it.scriptSteps
                // the color theme picked last time for this skin, as Winamp keeps it per skin
                if (check ==
                    null
                ) {
                    prefs.getString(themeKey, null)?.let { theme -> it.selectGammaSet(theme) }
                } else {
                    check.theme?.let { t -> it.selectGammaSet(t) }
                }
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
                val warmHost = AndroidSkinHost(WithGeneratedImages(files), context.cacheDir)
                val again = SkinRuntime(files, warmHost, FakePlayer())
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
            rt.currentGammaSet?.let { theme ->
                if (theme != prefs.getString(themeKey, null)) prefs.edit().putString(themeKey, theme).apply()
            }
            frontOpen = rt.topWindow != null
            if (changed || host.version != seen) {
                seen = host.version
                frame++
                logText = host.log.joinToString("\n")
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
                            val fit = scaleFor(px, layout.minW, layout.minH)
                            val w = maxOf(layout.minW, (px.width / fit).toInt())
                            val h = maxOf(layout.minH, (px.height / fit).toInt())
                            if (layout.size != w to h) {
                                layout.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(w), Value.Int(h)))
                            }
                        }
                        val (lw, lh) = baseW.takeIf { it > 0 }?.let { it to layout.size.second } ?: layout.size
                        val s = scaleFor(px, lw, lh)
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
                                val scale = fitScale(size, layout, baseW)
                                // 24 dp beside a control still counts: every control answers across 48 dp
                                rt.touchSlop = kotlin.math.ceil(24.dp.toPx() / scale).toInt()
                                val down = awaitFirstDown()
                                val downAt = System.currentTimeMillis()
                                var moved = false
                                val left = if (check == null) leftFor(size.width.toFloat(), layout.contentRight(), scale) else 0f

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
                    val scale = fitScale(IntSize(size.width.toInt(), size.height.toInt()), layout, baseW)
                    val stack =
                        runCatching { rt.drawWindows() }.getOrElse { e ->
                            host.log("draw: $e")
                            emptyList()
                        }
                    val windowsDone = System.nanoTime()
                    // the stack centered across the screen, as the classic player places its windows
                    val left = if (check == null) leftFor(size.width, layout.contentRight(), scale) else 0f
                    translate(left, 0f) {
                        scale(scale, scale, pivot = Offset.Zero) {
                            for ((r, cmds) in stack) {
                                // each window's edge clips what sticks out, as its desktop window region did
                                translate(r.left.toFloat(), r.top.toFloat()) {
                                    clipRect(0f, 0f, r.width.toFloat(), r.height.toFloat()) {
                                        for (cmd in cmds) drawCmd(cmd, host)
                                    }
                                }
                            }
                        }
                    }
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
    // the reference VM has Winamp's library inside the skin; the phone's library is its own screen
    rt.haveLibrary = check != null
    // Winamp Modern ships its drawer animation off; start with it on
    rt.storedSettings["Animate Config Drawer"] = "1"
    if (check == null) {
        // Bento opens the component it remembers, "Media Library" at first; Andamp's library is
        // a screen of its own, so start Bento on its video tab instead (its playlist tab would
        // pull the playlist out of the top-right pane and collapse that pane)
        rt.privateStrings["${file.nameWithoutExtension.lowercase()}/component"] = "Video"
    }
}

/** The scale for [layout] in [size]: from its visible width at the start ([baseW]) and its height. */
private fun fitScale(
    size: IntSize,
    layout: nl.mattix.andamp.modernskin.skin.Layout,
    baseW: Int,
): Float = scaleFor(size, baseW.takeIf { it > 0 } ?: layout.size.first, layout.size.second)

/** Where a stack [stackW] layout pixels wide starts so it sits centered in [width] at [scale]. */
private fun leftFor(
    width: Float,
    stackW: Int,
    scale: Float,
): Float = maxOf(0f, kotlin.math.floor((width - stackW * scale) / 2f))

private fun scaleFor(
    size: IntSize,
    lw: Int,
    lh: Int,
): Float {
    if (lw <= 0 || lh <= 0) return 1f
    val s = minOf(size.width.toFloat() / lw, size.height.toFloat() / lh)
    // whole-number scaling keeps the pixels crisp, like the classic windows; a wide layout gets what fits
    return if (s >= 2f) kotlin.math.floor(s) else s
}

@Suppress("ComplexCondition")
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImageCmd(
    cmd: DrawCmd.Image,
    host: AndroidSkinHost,
    blend: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
    mask: Boolean = false,
) {
    val img = (if (mask) host.cutMask(cmd.bitmap.file) else host.image(cmd.bitmap.file, cmd.gamma)) ?: return
    val full = host.imageSize(cmd.bitmap.file) ?: return
    val sw = cmd.bitmap.w ?: (full.first - cmd.bitmap.x)
    val sh = cmd.bitmap.h ?: (full.second - cmd.bitmap.y)
    if (sw <= 0 || sh <= 0 || cmd.dst.width <= 0 || cmd.dst.height <= 0) return
    // a bitmap element may claim more than its sheet holds: draw the part that exists, unstretched
    val cw = minOf(sw, full.first - cmd.bitmap.x)
    val ch = minOf(sh, full.second - cmd.bitmap.y)
    if (cw <= 0 || ch <= 0) return
    // the piece cut out: scaled on the phone, a part of a sheet would take in the pixels next to
    // it at its edges (Winamp3x's sheets keep magenta between their parts)
    val piece = host.piece(img, "${cmd.bitmap.file}|${cmd.gamma}|$mask", cmd.bitmap.x, cmd.bitmap.y, cw, ch)
    val dw = cmd.dst.width * cw / sw
    val dh = cmd.dst.height * ch / sh
    if (!cmd.smooth && (dw != cw || dh != ch) && dw > 0 && dh > 0) {
        // stretched as Winamp stretches: each pixel from source row/column floor(d * src / dst)
        val stretched =
            host.stretched(
                piece,
                "${cmd.bitmap.file}|${cmd.gamma}|$mask|${cmd.bitmap.x},${cmd.bitmap.y},$cw,$ch",
                dw,
                dh,
            )
        drawImage(
            stretched,
            dstOffset = IntOffset(cmd.dst.left, cmd.dst.top),
            alpha = cmd.alpha / 255f,
            blendMode = blend,
            filterQuality = FilterQuality.None,
        )
        return
    }
    drawImage(
        piece,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(cw, ch),
        dstOffset = IntOffset(cmd.dst.left, cmd.dst.top),
        dstSize = IntSize(cmd.dst.width * cw / sw, cmd.dst.height * ch / sh),
        alpha = cmd.alpha / 255f,
        blendMode = blend,
        filterQuality = if (cmd.smooth) FilterQuality.Low else FilterQuality.None,
    )
}

// one branch per draw command
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCmd(
    cmd: DrawCmd,
    host: AndroidSkinHost,
) {
    when (cmd) {
        is DrawCmd.Image -> {
            drawImageCmd(cmd, host)
        }

        is DrawCmd.WindowStart -> {
            // the window's content in a layer as large as its shape, masked by it at WindowEnd
            val b =
                cmd.region.bounds() ?: nl.mattix.andamp.modernskin.skin
                    .Rect(0, 0, 1, 1)
            drawContext.canvas.saveLayer(
                androidx.compose.ui.geometry
                    .Rect(0f, 0f, b.right.toFloat(), b.bottom.toFloat()),
                androidx.compose.ui.graphics
                    .Paint(),
            )
            host.windows.addLast(cmd)
        }

        DrawCmd.WindowEnd -> {
            val start = host.windows.removeLastOrNull()
            if (start != null) {
                host
                    .shapeMask(
                        start.region,
                        this,
                    )?.let { drawImage(it, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn) }
            }
            drawContext.canvas.restore()
        }

        is DrawCmd.Fill -> {
            drawRect(
                Color(cmd.color.value or 0xFF000000.toInt()),
                Offset(cmd.rect.left.toFloat(), cmd.rect.top.toFloat()),
                Size(cmd.rect.width.toFloat(), cmd.rect.height.toFloat()),
                alpha =
                    cmd.alpha / 255f,
            )
        }

        is DrawCmd.Text -> {
            val r = cmd.rect
            clipRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()) {
                drawIntoCanvas { canvas ->
                    val paint = host.paintFor(cmd.font, cmd.size, cmd.bold)
                    val width = paint.measureText(cmd.text)
                    val x =
                        when (cmd.align) {
                            Align.LEFT -> r.left.toFloat()
                            Align.CENTER -> r.left + (r.width - width) / 2f
                            Align.RIGHT -> r.right - width
                        } - cmd.scroll
                    val fm = paint.fontMetrics
                    // Windows' line where the font file gives it (whole pixels), else Android's
                    val (asc, desc) =
                        host.gdiLine(cmd.font, cmd.size, cmd.bold)?.let { (a, d) -> a.toFloat() to d.toFloat() }
                            ?: (-fm.ascent to fm.descent)
                    val y =
                        when (cmd.valign) {
                            VAlign.TOP -> r.top + asc
                            VAlign.CENTER -> r.top + ((r.height - (asc + desc)) / 2f).toInt() + asc
                            VAlign.BOTTOM -> r.bottom - desc
                        }
                    cmd.shadow?.let {
                        paint.color = it.value or 0xFF000000.toInt()
                        paint.alpha = cmd.alpha
                        canvas.nativeCanvas.drawText(cmd.text, x + cmd.shadowDx, y + cmd.shadowDy, paint)
                    }
                    paint.color = cmd.color.value or 0xFF000000.toInt()
                    paint.alpha = cmd.alpha
                    canvas.nativeCanvas.drawText(cmd.text, x, y, paint)
                }
            }
        }

        is DrawCmd.BitmapText -> {
            val img = host.image(cmd.font.file, cmd.gamma) ?: return
            val f = cmd.font
            val advance = f.charWidth + f.hSpacing
            val colon = cmd.colonWidth ?: advance
            val total = cmd.text.sumOf { if (it == ':') colon else advance }
            var x =
                when (cmd.align) {
                    Align.LEFT -> cmd.rect.left
                    Align.CENTER -> cmd.rect.left + (cmd.rect.width - total) / 2
                    Align.RIGHT -> cmd.rect.right - total
                } - cmd.scroll
            val y =
                when (cmd.valign) {
                    VAlign.TOP -> cmd.rect.top
                    VAlign.CENTER -> cmd.rect.top + (cmd.rect.height - f.charHeight) / 2
                    VAlign.BOTTOM -> cmd.rect.bottom - f.charHeight
                }
            val r = cmd.rect
            clipRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()) {
                for (c in cmd.text) {
                    val cell = f.cell(c)
                    if (cell != null) {
                        drawImage(
                            img,
                            srcOffset = IntOffset(f.ox + cell.first * f.charWidth, f.oy + cell.second * f.charHeight),
                            srcSize = IntSize(f.charWidth, f.charHeight),
                            dstOffset = IntOffset(x, y),
                            dstSize = IntSize(f.charWidth, f.charHeight),
                            alpha = cmd.alpha / 255f,
                            filterQuality = FilterQuality.None,
                        )
                    }
                    x += if (c == ':') colon else advance
                }
            }
        }

        is DrawCmd.Pixels -> {
            val bmp =
                android.graphics.Bitmap
                    .createBitmap(
                        cmd.argb,
                        cmd.w,
                        cmd.h,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    ).asImageBitmap()
            drawImage(
                bmp,
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                srcSize =
                    androidx.compose.ui.unit
                        .IntSize(cmd.w, cmd.h),
                dstOffset =
                    androidx.compose.ui.unit
                        .IntOffset(cmd.rect.left, cmd.rect.top),
                dstSize =
                    androidx.compose.ui.unit
                        .IntSize(cmd.rect.width, cmd.rect.height),
                alpha = cmd.alpha / 255f,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )
        }

        is DrawCmd.Clip -> {
            drawContext.canvas.save()
            drawContext.canvas.clipRect(
                cmd.rect.left.toFloat(),
                cmd.rect.top.toFloat(),
                cmd.rect.right.toFloat(),
                cmd.rect.bottom.toFloat(),
            )
        }

        DrawCmd.Unclip -> {
            drawContext.canvas.restore()
        }
    }
}
