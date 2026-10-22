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
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Gamma
import nl.mattix.andamp.modernskin.skin.GdiLineMetrics
import nl.mattix.andamp.modernskin.skin.ImageLimits
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.Text
import nl.mattix.andamp.modernskin.skin.WithGeneratedImages
import java.io.File
import java.security.MessageDigest

/**
 * The phone's side of one loaded modern skin: decodes its pictures, measures its text and keeps
 * what it made from them (pieces of sheets, tinted and stretched copies, window shapes), so a
 * frame only draws. [drawWalStack] draws with it. Not thread-safe: one thread uses it.
 *
 * [files] are the skin's files as [WalFiles.open] gives them; the pictures Wasabi generates
 * (`$solid`) are answered here.
 */
internal class WalHost(
    files: SkinFiles,
    private val cacheDir: File,
) : SkinHost {
    private val files = WithGeneratedImages(files)

    /** The last log lines, newest last, for a screen that shows them. */
    val recentLog = ArrayDeque<String>()

    /** Counts log lines and redraw requests, so a screen can tell it has something new to draw. */
    var version = 0
        private set

    private val sizes = HashMap<String, Pair<Int, Int>?>()
    private val images = Lru<String, ImageBitmap?>(MAX_IMAGES)
    private val tinted = Lru<Pair<String, Gamma>, ImageBitmap?>(MAX_TINTED)
    private val cutMasks = Lru<String, ImageBitmap?>(MAX_CUT_MASKS)
    private val pieces = Lru<String, ImageBitmap>(MAX_PIECES)
    private val stretchedPieces = Lru<String, ImageBitmap>(MAX_STRETCHED)
    private val stablePixels = Lru<IntArray, ImageBitmap>(MAX_STABLE_PIXELS)
    private val shapeMasks = Lru<String, ImageBitmap>(MAX_SHAPE_MASKS)
    private val typefaces = HashMap<String, Typeface?>()
    private val gdiLines = HashMap<String, GdiLineMetrics?>()

    /** For the engine's text measuring. */
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** For drawing text, whose color the renderer sets. */
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun imageSize(file: String): Pair<Int, Int>? =
        sizes.getOrPut(file.lowercase()) {
            val bytes = files.read(file) ?: return@getOrPut null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val tooLarge = opts.outWidth.toLong() * opts.outHeight > ImageLimits.MAX_PIXELS
            if (opts.outWidth > 0 && !tooLarge) opts.outWidth to opts.outHeight else null
        }

    fun image(file: String): ImageBitmap? =
        images.getOrPut(file.lowercase()) {
            val bytes = files.read(file) ?: return@getOrPut null
            // a picture claiming more pixels than a skin can need is not decoded
            if (ImageLimits.tooLarge(bytes)) return@getOrPut null
            val opts = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }

    /** The image with a color theme applied, made once per file and tint. */
    fun image(
        file: String,
        gamma: Gamma?,
    ): ImageBitmap? {
        if (gamma == null) return image(file)
        return tinted.getOrPut(file.lowercase() to gamma) {
            val src = image(file)?.asAndroidBitmap() ?: return@getOrPut null
            mapPixels(src) { gamma.apply(it) }
        }
    }

    /** A region picture as a mask: opaque where it cuts, clear elsewhere. */
    fun cutMask(file: String): ImageBitmap? =
        cutMasks.getOrPut(file.lowercase()) {
            val src = image(file)?.asAndroidBitmap() ?: return@getOrPut null
            mapPixels(src) { if (DrawCmd.isCutPixel(it)) OPAQUE_BLACK else 0 }
        }

    /** The part of [sheet] at x, y, w, h cut out as a separate picture, made once. */
    fun piece(
        sheet: ImageBitmap,
        sheetKey: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): ImageBitmap {
        val whole = x == 0 && y == 0
        if (whole && w == sheet.width && h == sheet.height) return sheet
        return pieces.getOrPut("$sheetKey|$x,$y,$w,$h") {
            Bitmap.createBitmap(sheet.asAndroidBitmap(), x, y, w, h).asImageBitmap()
        }
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
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

    /** A pixel picture as a bitmap; a stable one (a gradient) is made once and kept by its array. */
    fun pixels(cmd: DrawCmd.Pixels): ImageBitmap {
        fun make() = Bitmap.createBitmap(cmd.argb, cmd.w, cmd.h, Bitmap.Config.ARGB_8888).asImageBitmap()
        return if (cmd.stable) stablePixels.getOrPut(cmd.argb) { make() } else make()
    }

    /** A window's shape rendered once as a mask, by what it is made of: a shape changes rarely, frames often. */
    fun shapeMask(
        region: Region,
        scope: DrawScope,
    ): ImageBitmap? {
        val b = region.bounds() ?: return null
        if (b.right <= 0 || b.bottom <= 0) return null
        return shapeMasks.getOrPut(regionKey(region)) {
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
     * A font a skin names without a file is a Windows system font; the app carries free stand-ins
     * whose widths match closely (DejaVu Sans Condensed for Tahoma: Bento's tabs are sized by the
     * measured text). Bold has a separate file there, so no fake bold.
     */
    private fun standIn(
        font: FontDef?,
        bold: Boolean,
    ): Typeface? {
        val path = standInPath(font, bold) ?: return null
        return typefaces.getOrPut(path) { AndampDefaultSet.read(path)?.let(::typefaceOf) }
    }

    private fun typeface(font: FontDef?): Typeface? {
        if (font == null) return null
        return typefaces.getOrPut(font.id) { files.read(font.file)?.let(::typefaceOf) }
    }

    /**
     * A typeface from a font file's bytes. Android reads fonts from files, so the bytes are kept in
     * the cache under a name made from their content: two skins that call different fonts by the
     * same id get different files, and a file is never half written.
     */
    private fun typefaceOf(bytes: ByteArray): Typeface? {
        val f = File(cacheDir, "wal-font-${sha1(bytes)}.ttf")
        if (!f.exists()) {
            val tmp = File(cacheDir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        return runCatching { Typeface.createFromFile(f) }.getOrNull()
    }

    /** Ascent and descent in whole pixels as Windows lays the font out; null when its file gives none. */
    fun gdiLine(
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Pair<Int, Int>? {
        val f = font ?: return null
        val standIn = standInPath(f, bold)
        val m =
            gdiLines.getOrPut(standIn ?: f.id) {
                val bytes = if (standIn != null) AndampDefaultSet.read(standIn) else files.read(f.file)
                bytes?.let { GdiLineMetrics.read(it) }
            }
        return m?.at(Text.emSize(size))
    }

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

    override fun log(message: String) {
        Log.i(TAG, message)
        recentLog.addLast(message)
        while (recentLog.size > MAX_LOG) recentLog.removeFirst()
        version++
    }

    // the library is a separate Andamp screen, never open in a skin; every other window (the playlist
    // included) is the skin's, open when its docked window shows
    override fun windowOpen(kind: String): Boolean? = if (kind == "ml") false else null

    override fun pixel(
        file: String,
        x: Int,
        y: Int,
    ): Int? = image(file)?.asAndroidBitmap()?.takeIf { x >= 0 && y >= 0 && x < it.width && y < it.height }?.getPixel(x, y)

    override fun invalidate() {
        version++
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

        /** Windows fonts skins name without a file, and the free font in Andamp's default set that stands in. */
        private val STAND_INS = mapOf("tahoma" to "DejaVuSansCondensed")

        private const val MAX_LOG = 12
        private const val MAX_IMAGES = 256
        private const val MAX_TINTED = 256
        private const val MAX_CUT_MASKS = 64
        private const val MAX_PIECES = 1024

        /** Stretched pictures kept at once (a resized window makes new sizes). */
        private const val MAX_STRETCHED = 512

        /** Pictures from stable pixel arrays kept at once (one per gradient on screen). */
        private const val MAX_STABLE_PIXELS = 64

        /** Window shape masks kept at once. */
        private const val MAX_SHAPE_MASKS = 16

        private const val OPAQUE_BLACK = 0xFF000000.toInt()

        private fun standInPath(
            font: FontDef?,
            bold: Boolean,
        ): String? {
            val base = STAND_INS[font?.takeIf { it.file.isEmpty() }?.id ?: return null] ?: return null
            return "fonts/$base${if (bold) "-Bold" else ""}.ttf"
        }

        private fun mapPixels(
            src: Bitmap,
            f: (Int) -> Int,
        ): ImageBitmap {
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            for (i in px.indices) px[i] = f(px[i])
            return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

        private fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
