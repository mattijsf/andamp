// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import nl.mattix.andamp.modernskin.skin.BitmapDef
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.FontFace
import nl.mattix.andamp.modernskin.skin.Gamma
import nl.mattix.andamp.modernskin.skin.ImageLimits
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.Text
import nl.mattix.andamp.modernskin.skin.WithGeneratedImages
import nl.mattix.andamp.state.Flush
import nl.mattix.andamp.state.forceToDisk
import nl.mattix.andamp.state.writeFlushed
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's side of one loaded modern skin: decodes its pictures, measures its text and keeps
 * what it made from them (pieces of sheets, tinted and stretched copies, window shapes), so a
 * frame only draws. [drawWalStack] draws with it.
 *
 * Two threads use it: the engine thread, through [SkinHost] (sizes, text widths, pixels, the log,
 * popup menus), and the main thread, which draws. The caches both read (sizes, decoded pictures, typefaces) are
 * shared under a lock, with decoding done outside it; everything the drawing alone makes belongs
 * to the main thread, and the measuring paint to the engine thread.
 *
 * [files] are the skin's files as [WalFiles.open] gives them; the pictures Wasabi generates
 * (`$solid`) are answered here. [windowOpen] answers whether a window the app shows in place of
 * the skin's is open, by kind ("ml" the library, "avs" the visualizer); null leaves it to the skin.
 * [actions] answers the button actions the app handles outside the skin.
 * What the drawing makes is kept as [drawing] says: what the last frame drew, and the rest up to
 * [drawingBudget] bytes.
 */
@Suppress("TooManyFunctions") // every service SkinHost asks of the app, plus the caches the drawing reads
internal class WalHost(
    files: SkinFiles,
    private val cacheDir: File,
    windowOpen: (kind: String) -> Boolean? = LIBRARY_CLOSED,
    drawingBudget: Long = WalDrawing.BUDGET,
    private val actions: (action: String, param: String?) -> Unit = { _, _ -> },
) : SkinHost {
    private val files = WithGeneratedImages(files)

    private val openAnswer = windowOpen

    /** The last log lines, newest last. */
    private val recentLog = ArrayDeque<String>()

    /** The popup menu a script waits on, which the screen shows and answers. */
    val menus = WalMenuWait()

    /** Counts log lines and redraw requests, so the engine can tell it has something new to draw. */
    @Volatile
    var version = 0
        private set

    /** Guards [sizes], [images], [typefaces], [recentLog] and [version]'s increments. */
    private val shared = Any()

    // shared by both threads, under [shared]
    private val sizes = HashMap<String, Pair<Int, Int>?>()
    private val images = Lru<String, ImageBitmap?>(MAX_IMAGES)
    private val typefaces = HashMap<String, Typeface?>()

    /** File names as the caches know them, lowercased once; read by both threads. */
    private val lowered = ConcurrentHashMap<String, String>()

    /** What the drawing made: tinted sheets, cut masks, pieces, stretched pieces, gradients and shapes. The main thread's. */
    val drawing = WalDrawing(drawingBudget)

    // the main thread's

    /** Filled to look a picture up in [drawing]; a copy is what is kept. */
    private val probe = WalDrawing.Key()

    /** Each window shape's key in [drawing], worked out once for each shape the engine hands over. */
    private val shapeKeys = WeakHashMap<Region, WalDrawing.ShapeKey>()

    /** For the engine's text measuring. */
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** For drawing text, whose color the renderer sets. */
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** [file] lowercased, worked out once per name. */
    private fun lower(file: String): String = lowered.getOrPut(file) { file.lowercase() }

    override fun imageSize(file: String): Pair<Int, Int>? =
        cached(sizes, lower(file)) {
            val bytes = files.read(file) ?: return@cached null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val tooLarge = opts.outWidth.toLong() * opts.outHeight > ImageLimits.MAX_PIXELS
            if (opts.outWidth > 0 && !tooLarge) opts.outWidth to opts.outHeight else null
        }

    /** How many pictures were decoded from the skin's files, for tests. */
    @Volatile
    var decoded = 0
        private set

    fun image(file: String): ImageBitmap? =
        cached(images, lower(file)) {
            decoded++
            val bytes = files.read(file) ?: return@cached null
            // a picture claiming more pixels than a skin can need is not decoded
            if (ImageLimits.tooLarge(bytes)) return@cached null
            val opts = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }

    /**
     * [key]'s value in a shared [map], made with [make] outside the lock when missing, so neither
     * thread waits while the other decodes. Two threads may make the same value once; the first
     * kept wins.
     */
    private fun <K, V> cached(
        map: MutableMap<K, V?>,
        key: K,
        make: () -> V?,
    ): V? {
        synchronized(shared) { if (key in map) return map[key] }
        val made = make()
        synchronized(shared) {
            if (key in map) return map[key]
            map[key] = made
        }
        return made
    }

    /** The image with a color theme applied, made once per file and tint. */
    fun image(
        file: String,
        gamma: Gamma?,
    ): ImageBitmap? = if (gamma == null) image(file) else tinted(file, gamma).picture

    /** [file] tinted by [gamma], as [drawing] keeps it. */
    private fun tinted(
        file: String,
        gamma: Gamma,
    ): WalDrawing.Entry =
        drawing.entry(probe.of(WalDrawing.Key.TINTED, lower(file), gamma), themed = true) {
            image(file)?.asAndroidBitmap()?.let { src -> mapPixels(src) { gamma.apply(it) } }
        }

    /** A region picture as a mask, as [drawing] keeps it: opaque where it cuts, clear elsewhere. */
    private fun cutMask(file: String): WalDrawing.Entry =
        drawing.entry(probe.of(WalDrawing.Key.CUT, lower(file), null)) {
            image(file)?.asAndroidBitmap()?.let { src -> mapPixels(src) { if (DrawCmd.isCutPixel(it)) OPAQUE_BLACK else 0 } }
        }

    /**
     * The part of its sheet [bitmap] names, cut out as a separate picture once: from the sheet
     * tinted by [gamma], or from its cut mask when [mask]. The part that exists is cut when the
     * element claims more than the sheet holds; null when nothing of it is on the sheet. Its size
     * is what the sheet holds.
     */
    fun piece(
        bitmap: BitmapDef,
        gamma: Gamma?,
        mask: Boolean,
    ): ImageBitmap? {
        val tint = if (mask) null else gamma
        val key = probe.of(if (mask) WalDrawing.Key.MASK_PIECE else WalDrawing.Key.PIECE, lower(bitmap.file), tint, bitmap)
        drawing.find(key)?.let { return it.picture }
        val kept = key.copy()
        // a tinted sheet or a cut mask stays in the drawing while pieces cut from it are drawn; an
        // untinted sheet is the decoded picture, which [images] keeps
        val source =
            when {
                mask -> cutMask(bitmap.file)
                tint != null -> tinted(bitmap.file, tint)
                else -> null
            }
        val sheet = if (source != null) source.picture else image(bitmap.file)
        val piece = sheet?.let { cut(it, bitmap) }
        return drawing.keep(kept, piece, themed = tint != null, source = source).picture
    }

    /** [piece] ([bitmap]'s, as [piece] gave it) stretched to w x h with Winamp's floor mapping, made once per size. */
    fun stretched(
        piece: ImageBitmap,
        bitmap: BitmapDef,
        gamma: Gamma?,
        mask: Boolean,
        w: Int,
        h: Int,
    ): ImageBitmap {
        val tint = if (mask) null else gamma
        val kind = if (mask) WalDrawing.Key.MASK_STRETCHED else WalDrawing.Key.STRETCHED
        val key = probe.of(kind, lower(bitmap.file), tint, bitmap, w, h)
        return drawing.getOrPut(key, themed = tint != null) {
            val src = piece.asAndroidBitmap()
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            val out = IntArray(w * h)
            for (y in 0 until h) {
                val sy = (y.toLong() * src.height / h).toInt()
                for (x in 0 until w) out[y * w + x] = px[sy * src.width + (x.toLong() * src.width / w).toInt()]
            }
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    /**
     * A pixel picture as a bitmap. A stable one (a gradient) is made once and kept by its array;
     * one that changes (a visualizer) is filled into a bitmap [drawing] keeps for its size.
     */
    fun pixels(cmd: DrawCmd.Pixels): ImageBitmap {
        if (!cmd.stable) return drawing.changing(cmd)
        return drawing.getOrPut(cmd.argb) {
            Bitmap.createBitmap(cmd.argb, cmd.w, cmd.h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    /**
     * A window's shape rendered once as a mask, by what it is made of: a shape changes rarely,
     * frames often. What it is made of is worked out once for each region the engine hands over.
     */
    fun shapeMask(
        region: Region,
        scope: DrawScope,
    ): ImageBitmap? {
        val b = region.bounds() ?: return null
        if (b.right <= 0 || b.bottom <= 0) return null
        val key = shapeKeys.getOrPut(region) { WalDrawing.ShapeKey(regionKey(region)) }
        return drawing.getOrPut(key) {
            val img = ImageBitmap(b.right, b.bottom)
            val size = Size(b.right.toFloat(), b.bottom.toFloat())
            CanvasDrawScope().draw(scope, scope.layoutDirection, Canvas(img), size) {
                region.eval(
                    MaskCanvas(
                        this,
                        androidx.compose.ui.geometry
                            .Rect(Offset.Zero, size),
                    ),
                )
            }
            img
        }
    }

    /** Draws a region into a mask: pictures and boxes opaque, combined by the region's operations. */
    private inner class MaskCanvas(
        private val scope: DrawScope,
        private val whole: androidx.compose.ui.geometry.Rect,
    ) : Region.Canvas {
        override fun picture(
            image: DrawCmd.Image,
            hard: Boolean,
        ) = scope.drawImageCmd(image, this@WalHost, mask = hard)

        override fun box(rect: nl.mattix.andamp.modernskin.skin.Rect) =
            scope.drawRect(
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
                    Region.Blend.ADD -> BlendMode.SrcOver
                    Region.Blend.KEEP -> BlendMode.DstIn
                    Region.Blend.CUT -> BlendMode.DstOut
                }
            scope.drawContext.canvas.saveLayer(
                whole,
                androidx.compose.ui.graphics
                    .Paint()
                    .apply { blendMode = mode },
            )
            body()
            scope.drawContext.canvas.restore()
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
                val add = r.add.joinToString(",", transform = ::regionKey)
                val keep = r.keep.joinToString(",", transform = ::regionKey)
                val cut = r.cut.joinToString(",", transform = ::regionKey)
                "O(${r.base?.let(::regionKey)}|$add|$keep|$cut)"
            }
        }

    /**
     * The free font the engine picks for a Windows system font a skin names without a file
     * ([FontFace.StandIn]), from Andamp's default set; null for any other font. Bold has a separate
     * file there, so no fake bold.
     */
    private fun standIn(
        font: FontDef?,
        bold: Boolean,
    ): Typeface? {
        val path = (FontFace.of(font, bold) as? FontFace.StandIn)?.file ?: return null
        return cached(typefaces, path) { AndampDefaultSet.read(path)?.let(::typefaceOf) }
    }

    private fun typeface(font: FontDef?): Typeface? {
        if (font == null) return null
        return cached(typefaces, font.id) { files.read(font.file)?.let(::typefaceOf) }
    }

    /** A typeface from a font file's bytes, read from the file [fontFile] keeps them in. */
    private fun typefaceOf(bytes: ByteArray): Typeface? =
        runCatching { Typeface.createFromFile(fontFile(cacheDir, bytes)) }.getOrNull()

    /** The paint the renderer draws [font] with; it sets the color itself. */
    fun drawingPaint(
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Paint = setUp(drawPaint, font, size, bold)

    private fun setUp(
        paint: Paint,
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Paint {
        val standIn = standIn(font, bold)
        paint.typeface = standIn ?: typeface(font) ?: Typeface.DEFAULT
        paint.isFakeBoldText = bold && standIn == null
        paint.textSize = Text.emSize(size)
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
    ): Int = setUp(measurePaint, font, size, bold).measureText(text).toInt()

    override fun textAdvance(
        text: String,
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Float = setUp(measurePaint, font, size, bold).measureText(text)

    override fun lineMetrics(
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Pair<Float, Float> = setUp(measurePaint, font, size, bold).fontMetrics.let { -it.ascent to it.descent }

    override fun log(message: String) {
        Log.i(TAG, message)
        synchronized(shared) {
            recentLog.addLast(message)
            while (recentLog.size > MAX_LOG) recentLog.removeFirst()
            version++
        }
    }

    /** The last log lines, newest last. */
    fun logSnapshot(): List<String> = synchronized(shared) { recentLog.toList() }

    override fun windowOpen(kind: String): Boolean? = openAnswer(kind)

    override fun pixel(
        file: String,
        x: Int,
        y: Int,
    ): Int? = image(file)?.asAndroidBitmap()?.takeIf { x >= 0 && y >= 0 && x < it.width && y < it.height }?.getPixel(x, y)

    override fun pixels(
        file: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): IntArray? {
        if (minOf(x, y) < 0 || minOf(w, h) <= 0) return null
        val bitmap = image(file)?.asAndroidBitmap() ?: return null
        if (x + w > bitmap.width || y + h > bitmap.height) return null
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, x, y, w, h) }
    }

    override fun invalidate() {
        synchronized(shared) { version++ }
    }

    override fun chooseFromMenu(menu: SkinMenu): Int? = menus.choose(menu)

    override fun hostAction(
        action: String,
        param: String?,
    ) = actions(action, param)

    /**
     * Lets go of what was made for drawing: the decoded pictures, their tinted, cut, stretched and
     * shape copies, and the text line metrics, so a skin off screen holds less memory. The next
     * frame makes again what it needs. On the main thread.
     */
    fun trimDrawing() {
        drawing.clear()
        shapeKeys.clear()
        synchronized(shared) { images.clear() }
    }

    /** A map that keeps the [max] entries used last. */
    private class Lru<K, V>(
        private val max: Int,
    ) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }

    companion object {
        /** The log tag, also what the debug checks wait for. */
        const val TAG = "ModernSkin"

        /**
         * The library is a separate Andamp screen, never open in a skin; every other window (the
         * playlist included) is the skin's, open when its docked window shows.
         */
        val LIBRARY_CLOSED: (String) -> Boolean? = { kind -> if (kind == "ml") false else null }

        private const val MAX_LOG = 12
        private const val MAX_IMAGES = 256

        private const val OPAQUE_BLACK = 0xFF000000.toInt()

        private fun mapPixels(
            src: Bitmap,
            f: (Int) -> Int,
        ): ImageBitmap {
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            for (i in px.indices) px[i] = f(px[i])
            return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

        /** The part of [sheet] that [bitmap] names and the sheet holds, or the sheet itself when that is all of it. */
        private fun cut(
            sheet: ImageBitmap,
            bitmap: BitmapDef,
        ): ImageBitmap? {
            val sw = bitmap.w ?: (sheet.width - bitmap.x)
            val sh = bitmap.h ?: (sheet.height - bitmap.y)
            val cw = minOf(sw, sheet.width - bitmap.x)
            val ch = minOf(sh, sheet.height - bitmap.y)
            return when {
                bitmap.x < 0 || bitmap.y < 0 || cw <= 0 || ch <= 0 -> null
                bitmap.x == 0 && bitmap.y == 0 && cw == sheet.width && ch == sheet.height -> sheet
                else -> Bitmap.createBitmap(sheet.asAndroidBitmap(), bitmap.x, bitmap.y, cw, ch).asImageBitmap()
            }
        }

        private fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * The file in [cacheDir] that holds a font's [bytes], written when it is not there. Android
         * reads fonts from files, so the bytes are kept under a name made from their content: two
         * skins that call different fonts by the same id get different files. The bytes are flushed
         * to the disk before the rename that names the file, and a file whose size is not that of
         * the bytes, such as an empty one left when the phone switched off mid-write, is written
         * again. When that write fails, a short file is deleted, so the file is whole or missing and
         * reading a missing one as a font fails.
         */
        internal fun fontFile(
            cacheDir: File,
            bytes: ByteArray,
            flush: Flush = forceToDisk,
        ): File {
            val f = File(cacheDir, "wal-font-${sha1(bytes)}.ttf")
            if (f.length() != bytes.size.toLong()) {
                // named per thread, so two threads writing the same font do not mix their bytes
                val tmp = File(cacheDir, "${f.name}.${Thread.currentThread().id}.tmp")
                // a write cut short (a full disk) deletes tmp, and nothing is renamed into place
                val written = runCatching { tmp.writeFlushed(flush) { it.write(bytes) } }.isSuccess
                if (written && !tmp.renameTo(f)) tmp.delete()
                if (f.length() != bytes.size.toLong()) f.delete()
            }
            return f
        }
    }
}

/**
 * The pictures [WalHost] made for drawing a skin, on the main thread, each by what it was made
 * from. Drawing goes in frames ([begin], [end]): what a frame draws is always kept, and so is the
 * sheet a drawn piece was cut from, so a piece the next frame needs is cut without tinting the
 * sheet again. The rest is kept, the least recently drawn let go first, while it takes up to
 * [budget] bytes. A bitmap kept under two keys (a piece that is its whole sheet) counts once. A
 * frame in another color theme than the one before lets go of the tinted pictures it did not
 * draw. Window shapes are kept apart, up to [MAX_SHAPES] whatever their size, the least recently
 * drawn let go first: a shape that moves (a sliding drawer, an animation in it) makes a new one
 * each frame.
 *
 * A pixel picture that changes every frame (a visualizer) is filled into bitmaps kept by size and
 * by its place among the frame's pictures of that size, two for each, used in turn: a frame never
 * writes the bitmap the frame before it may still be showing. Outside a frame each one gets a new
 * bitmap.
 *
 * A drawing kept between frames ([Recorded], a layer recorded once and drawn as it is in later
 * frames) asks for its pictures only while it is recorded ([recording]). What it holds counts as
 * drawn in every frame that draws it again ([retain]), so neither the budget nor a theme switch
 * lets go of a picture it shows, and the bytes counted stay true. Its changing pixel pictures get
 * two bitmaps for each place in it, used in turn at each recording, so a bitmap it shows is never
 * written while it shows it.
 */
internal class WalDrawing(
    private val budget: Long,
) {
    /** A bitmap kept under one key or more: the bytes it takes, how many keys hold it, and the last frame that drew it. */
    internal class Held(
        val bytes: Long,
    ) {
        var keys = 0
        var frame = -1L
    }

    /**
     * A kept picture (null once it was let go, so a piece kept longer than its sheet does not hold
     * the sheet): whether a color theme tinted it, the kept picture it was cut from ([source]:
     * drawing this one counts as drawing that one), its bitmap, and the last frame that drew it.
     */
    internal class Entry(
        var picture: ImageBitmap?,
        val themed: Boolean,
        val source: Entry?,
        val held: Held?,
        var frame: Long,
        /** A window shape, kept apart from the rest. */
        val shape: Boolean = false,
    ) {
        /** False once it was let go. */
        var kept = true

        /** The recording that last took it into what it holds (see [Recorded.stamp]). */
        var heldBy = 0L
    }

    /**
     * What one drawing kept between frames holds of these pictures: those it asked for while it was
     * recorded, and the bitmaps of its changing pixel pictures, two for each place in it.
     */
    internal class Recorded {
        internal val held = ArrayList<Entry>()

        /** Names its last recording; each recording gets a new one. */
        internal var stamp = 0L
        private var place = 0
        private val buffers = ArrayList<Buffers>()

        /** A recording starts as [stamp]: what it holds is gathered again. */
        internal fun start(stamp: Long) {
            this.stamp = stamp
            held.clear()
            place = 0
        }

        /** [cmd]'s pixels in the bitmap of its place in this drawing that the recording before did not use. */
        internal fun pixels(cmd: DrawCmd.Pixels): ImageBitmap {
            val at = place++
            var buffer = buffers.getOrNull(at)
            if (buffer == null || buffer.w != cmd.w || buffer.h != cmd.h) {
                buffer = Buffers(cmd.w, cmd.h, at)
                if (at < buffers.size) buffers[at] = buffer else buffers.add(buffer)
            }
            val bitmap = buffer.next(stamp)
            bitmap.asAndroidBitmap().setPixels(cmd.argb, 0, cmd.w, 0, 0, cmd.w, cmd.h)
            return bitmap
        }
    }

    /** The drawing being recorded, which holds what is asked for meanwhile, or null. */
    private var recording: Recorded? = null
    private var stamps = 0L

    /**
     * Runs [body] as a recording of [into]: what it asks for is held by [into], which drops what
     * an earlier recording held. Recordings may nest; each holds what is asked for inside it alone.
     */
    fun <T> recording(
        into: Recorded,
        body: () -> T,
    ): T {
        val outer = recording
        recording = into
        into.start(++stamps)
        try {
            return body()
        } finally {
            recording = outer
        }
    }

    /** What [kept] holds, marked as drawn in this frame, which draws it again without asking for it. */
    fun retain(kept: Recorded) {
        for (e in kept.held) {
            if (e.shape) {
                e.frame = frame
            } else if (e.kept) {
                mark(e)
            }
        }
    }

    /** [e] is held by the drawing being recorded, if any. */
    private fun hold(e: Entry) {
        val r = recording ?: return
        if (e.heldBy == r.stamp) return
        e.heldBy = r.stamp
        r.held.add(e)
    }

    /** Kept pictures, the least recently looked up first. */
    private val entries = LinkedHashMap<Any, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    /** The bitmaps of [entries], each once. */
    private val holders = IdentityHashMap<ImageBitmap, Held>()

    /** Kept window shapes ([ShapeKey]), the least recently drawn first. */
    private val shapes = LinkedHashMap<Any, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    /** Bitmaps for changing pixel pictures; see [changing]. */
    private val buffers = ArrayList<Buffers>()

    /** The bytes the kept pictures take, each bitmap once; window shapes are not counted. */
    var bytes = 0L
        private set

    /** The bytes of the kept pictures the current frame drew, each bitmap once. */
    private var frameBytes = 0L

    /** The bytes the kept window shapes take. */
    var shapeBytes = 0L
        private set

    /** How many pictures were kept, for tests: tints, cut masks, pieces, stretched pieces, gradients and shapes. */
    var made = 0
        private set

    private var frame = 0L
    private var inFrame = false
    private var theme: String? = null
    private var themeChanged = false

    /** A frame starts, worn in color theme [themeKey] (null when that is not known). */
    fun begin(themeKey: String?) {
        frame++
        frameBytes = 0
        inFrame = true
        if (themeKey != null) {
            if (theme != null && themeKey != theme) themeChanged = true
            theme = themeKey
        }
    }

    /** The frame [begin] started has been drawn: what it did not draw is let go as far as the budget and the theme say. */
    fun end() {
        inFrame = false
        if (themeChanged) {
            themeChanged = false
            val it = entries.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.themed && !drawn(e)) {
                    it.remove()
                    letGo(e)
                }
            }
        }
        val shape = shapes.values.iterator()
        while (shapes.size > MAX_SHAPES && shape.hasNext()) {
            val e = shape.next()
            if (e.frame == frame) continue
            shape.remove()
            shapeBytes -= e.held?.bytes ?: 0L
        }
        val it = entries.values.iterator()
        while (bytes - frameBytes > budget && it.hasNext()) {
            val e = it.next()
            if (drawn(e)) continue
            it.remove()
            letGo(e)
        }
        for (i in buffers.indices.reversed()) if (buffers[i].frame != frame) buffers.removeAt(i)
    }

    /** Whether this frame drew [e], or its bitmap under another key, or a piece cut from it. */
    private fun drawn(e: Entry) = e.frame == frame || e.held?.frame == frame

    /** [e] and the picture it was cut from, marked as drawn in this frame. */
    private fun mark(e: Entry) {
        e.frame = frame
        e.held?.let(::mark)
        val source = e.source ?: return
        if (!source.kept) return
        source.frame = frame
        source.held?.let(::mark)
    }

    private fun mark(held: Held) {
        if (held.frame == frame) return
        held.frame = frame
        frameBytes += held.bytes
    }

    /** [e], taken out of [entries]: its bitmap's bytes no longer count once no other key holds it. */
    private fun letGo(e: Entry) {
        val picture = e.picture
        e.kept = false
        e.picture = null
        val held = e.held ?: return
        if (--held.keys > 0) return
        holders.remove(picture)
        bytes -= held.bytes
        if (held.frame == frame) frameBytes -= held.bytes
    }

    /** The picture kept under [key], marked as drawn in this frame, or null when none is kept. */
    internal fun find(key: Any): Entry? {
        if (key is ShapeKey) {
            return shapes[key]?.also {
                it.frame = frame
                hold(it)
            }
        }
        return entries[key]?.also {
            mark(it)
            hold(it)
        }
    }

    /**
     * Keeps [picture] under [key] as drawn in this frame; [themed] when a color theme tinted it,
     * [source] the kept picture it was cut from, if any.
     */
    internal fun keep(
        key: Any,
        picture: ImageBitmap?,
        themed: Boolean,
        source: Entry? = null,
    ): Entry {
        made++
        if (key is ShapeKey) {
            val e = Entry(picture, themed, null, picture?.let { Held(sizeOf(it)) }, frame, shape = true)
            shapes.put(key, e)?.let { shapeBytes -= it.held?.bytes ?: 0L }
            shapeBytes += e.held?.bytes ?: 0L
            hold(e)
            return e
        }
        val held =
            picture?.let { p ->
                holders.getOrPut(p) { Held(sizeOf(p)) }.also { if (it.keys++ == 0) bytes += it.bytes }
            }
        val e = Entry(picture, themed, source, held, frame)
        entries.put(key, e)?.let(::letGo)
        mark(e)
        hold(e)
        return e
    }

    /**
     * The picture kept under [key], or the one [make] makes, kept. A [Key] is copied before
     * [make] runs, so the probe it was filled into can be filled again inside.
     */
    inline fun entry(
        key: Any,
        themed: Boolean = false,
        make: () -> ImageBitmap?,
    ): Entry {
        find(key)?.let { return it }
        val kept = if (key is Key) key.copy() else key
        return keep(kept, make(), themed)
    }

    /** [entry]'s picture. */
    @Suppress("UNCHECKED_CAST")
    inline fun <T : ImageBitmap?> getOrPut(
        key: Any,
        themed: Boolean = false,
        make: () -> T,
    ): T = entry(key, themed, make).picture as T

    /**
     * [cmd]'s pixels in a bitmap kept for its size and its place among this frame's pictures of
     * that size; while a kept drawing is recorded, in that drawing's bitmap for its place in it.
     */
    fun changing(cmd: DrawCmd.Pixels): ImageBitmap {
        recording?.let { return it.pixels(cmd) }
        if (!inFrame) return Bitmap.createBitmap(cmd.argb, cmd.w, cmd.h, Bitmap.Config.ARGB_8888).asImageBitmap()
        var place = 0
        var buffer: Buffers? = null
        for (i in buffers.indices) {
            val b = buffers[i]
            if (b.w == cmd.w && b.h == cmd.h && b.frame == frame) place++
        }
        for (i in buffers.indices) {
            val b = buffers[i]
            if (b.w == cmd.w && b.h == cmd.h && b.place == place) buffer = b
        }
        if (buffer == null) buffer = Buffers(cmd.w, cmd.h, place).also { buffers.add(it) }
        val bitmap = buffer.next(frame)
        bitmap.asAndroidBitmap().setPixels(cmd.argb, 0, cmd.w, 0, 0, cmd.w, cmd.h)
        return bitmap
    }

    /** Lets go of every picture and bitmap. */
    fun clear() {
        for (e in entries.values) e.kept = false
        entries.clear()
        holders.clear()
        shapes.clear()
        buffers.clear()
        bytes = 0
        frameBytes = 0
        shapeBytes = 0
    }

    /** How many window shapes are kept, for tests. */
    val shapesKept: Int get() = shapes.size

    /** The bytes of the distinct bitmaps the kept pictures hold, counted again from them, for tests. */
    fun keptBytes(): Long =
        entries.values
            .mapNotNullTo(Collections.newSetFromMap(IdentityHashMap())) { it.picture }
            .sumOf { sizeOf(it) }

    /** The color theme tints of the kept pictures, for tests. */
    fun tints(): Set<Gamma> = entries.keys.mapNotNullTo(HashSet()) { (it as? Key)?.gamma }

    /** The two bitmaps of one size and place, and which one the last frame that used them drew. */
    private class Buffers(
        val w: Int,
        val h: Int,
        val place: Int,
    ) {
        private val bitmaps = arrayOfNulls<ImageBitmap>(2)
        private var turn = 0
        var frame = -1L
            private set

        /** The bitmap for [frame]: the other one than the last frame's. */
        fun next(frame: Long): ImageBitmap {
            if (frame != this.frame) {
                this.frame = frame
                turn = 1 - turn
            }
            return bitmaps[turn] ?: ImageBitmap(w, h).also { bitmaps[turn] = it }
        }
    }

    /**
     * What a picture was made from: its kind, the file (lowercased), the tint and, for a part of a
     * sheet, where it sits and the size it was stretched to. [WalHost] fills one to look up and
     * keeps a copy.
     */
    class Key {
        var kind = 0
            private set
        var file = ""
            private set
        var gamma: Gamma? = null
            private set
        private var x = 0
        private var y = 0
        private var w = 0
        private var h = 0
        private var dw = 0
        private var dh = 0

        /** This key, filled: a [part] of the sheet when given (no size taken as -1), stretched to [dw] x [dh]. */
        @Suppress("LongParameterList") // the fields of the key
        fun of(
            kind: Int,
            file: String,
            gamma: Gamma?,
            part: BitmapDef? = null,
            dw: Int = 0,
            dh: Int = 0,
        ): Key {
            this.kind = kind
            this.file = file
            this.gamma = gamma
            x = part?.x ?: 0
            y = part?.y ?: 0
            w = part?.w ?: -1
            h = part?.h ?: -1
            this.dw = dw
            this.dh = dh
            return this
        }

        fun copy(): Key =
            Key().also {
                it.kind = kind
                it.file = file
                it.gamma = gamma
                it.x = x
                it.y = y
                it.w = w
                it.h = h
                it.dw = dw
                it.dh = dh
            }

        override fun equals(other: Any?): Boolean =
            other is Key &&
                kind == other.kind &&
                x == other.x &&
                y == other.y &&
                w == other.w &&
                h == other.h &&
                dw == other.dw &&
                dh == other.dh &&
                file == other.file &&
                gamma == other.gamma

        override fun hashCode(): Int {
            var r = HASH * kind + x
            r = HASH * r + y
            r = HASH * r + w
            r = HASH * r + h
            r = HASH * r + dw
            r = HASH * r + dh
            r = HASH * r + file.hashCode()
            return HASH * r + (gamma?.hashCode() ?: 0)
        }

        companion object {
            const val TINTED = 1
            const val CUT = 2
            const val PIECE = 3
            const val MASK_PIECE = 4
            const val STRETCHED = 5
            const val MASK_STRETCHED = 6
            private const val HASH = 31
        }
    }

    /** A window shape's mask in [WalDrawing], by what the shape is made of. */
    data class ShapeKey(
        val structure: String,
    )

    companion object {
        /** The bytes of the kept pictures that the last frame did not draw, window shapes not included. */
        const val BUDGET = 48L * 1024 * 1024

        /** Window shapes kept, unless the last frame drew more. */
        const val MAX_SHAPES = 16

        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f

        private fun sizeOf(picture: ImageBitmap): Long = picture.asAndroidBitmap().allocationByteCount.toLong()
    }
}
