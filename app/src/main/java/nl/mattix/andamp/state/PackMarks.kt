// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import nl.mattix.andamp.core.packapi.PackApi
import kotlin.math.roundToInt

/**
 * The marks of the sources packs play ([SourceMark]), read from the pack's app without binding
 * it or loading its code.
 *
 * A pack can name its mark in its service's meta-data: a monochrome drawable for the outline font
 * ([PackApi.META_MARK]) and pixel art for the skin's ([PackApi.META_MARK_PIXELS]). Both are
 * optional. A pack that names no drawable, as every pack built before them, is marked from its
 * launcher icon: the monochrome layer of an adaptive icon (Android 13 and up), or else the shape
 * of its foreground layer. A pack that gives no pixel art gets pixel art made from that picture.
 * A picture too full or too empty to tell apart at that size is not taken, and the source's first
 * letter stands in.
 *
 * What is read is kept per pack, until [forget] is told the pack changed.
 */
object PackMarks {
    /** How large a picture is drawn to be read, in pixels. */
    private const val READ = 216

    /** How tall the kept shape is, in pixels: enough for the largest list text. */
    private const val MASK_H = 64

    /** An adaptive icon's layer is 108 units wide, its art kept to the middle 72. */
    private const val ADAPTIVE_INSET = 18f / 108f

    /** The alpha above which a pixel counts as ink. */
    private const val INK = 128

    /** The share of a picture's zone its ink may cover: less is a speck, more is a filled square, as a full-bleed layer is. */
    private const val MIN_COVER = 0.02f
    private const val MAX_COVER = 0.6f

    /** The share of the cells of made pixel art that may be ink: outside it, the art is a blob or a dot and says nothing. */
    private const val MIN_INK = 0.2f
    private const val MAX_INK = 0.8f

    private val kept = HashMap<String, SourceMark>()

    /** How many times a pack changed, as snapshot state, so what shows the marks reads them again. */
    var changes by mutableIntStateOf(0)
        private set

    /** The mark of the source the app [packageName] plays, called [name]; see [PackMarks]. */
    fun of(
        context: Context,
        packageName: String,
        name: String,
    ): SourceMark {
        synchronized(kept) { kept[packageName]?.let { return it } }
        val mark = runCatching { read(context.packageManager, packageName, name) }.getOrNull() ?: SourceMark.letter(name)
        synchronized(kept) { kept[packageName] = mark }
        return mark
    }

    /** The mark of [source] when a pack plays it, called [name]; null for a source no pack plays. */
    fun forSource(
        context: Context,
        source: MusicSource,
        name: String,
    ): SourceMark? = PackSources.found.firstOrNull { it.source == source }?.let { of(context, it.packageName, name) }

    /** The app [packageName] was installed, updated or removed: its mark is read again when next asked. */
    fun forget(packageName: String) {
        synchronized(kept) { kept.remove(packageName) }
        changes++
    }

    private fun read(
        manager: PackageManager,
        packageName: String,
        name: String,
    ): SourceMark {
        // the flag-taking overload is the only one below API 33
        @Suppress("DEPRECATION")
        val service =
            manager
                .queryIntentServices(Intent(PackApi.ACTION_BIND).setPackage(packageName), PackageManager.GET_META_DATA)
                .firstOrNull()
                ?.serviceInfo
        return markFrom(
            meta = service?.metaData,
            resources = runCatching { manager.getResourcesForApplication(packageName) }.getOrNull(),
            icon = runCatching { manager.getApplicationIcon(packageName) }.getOrNull(),
            name = name,
        )
    }

    /** The mark a pack's meta-data and launcher icon make; see [PackMarks]. */
    internal fun markFrom(
        meta: Bundle?,
        resources: Resources?,
        icon: Drawable?,
        name: String,
    ): SourceMark {
        fun resource(key: String) = meta?.getInt(key, 0)?.takeIf { it != 0 }
        val drawn =
            resource(PackApi.META_MARK)
                ?.let { id -> runCatching { resources?.getDrawable(id, null) }.getOrNull() }
                ?.let { maskOf(it, inset = 0f) }
        val mask = drawn ?: icon?.let(::iconMask)
        val given =
            resource(PackApi.META_MARK_PIXELS)
                ?.let { id -> runCatching { resources?.getStringArray(id)?.toList() }.getOrNull() }
                .orEmpty()
        val pixels = SourceMark.pixelsOrEmpty(given).ifEmpty { mask?.let(::pixelsOf).orEmpty() }
        return SourceMark(pixels = pixels, letter = SourceMark.letter(name).letter, mask = mask)
    }

    /**
     * The shape of a launcher icon: its monochrome layer, or else its foreground. A flat icon is a
     * picture in color, not a shape, and gives none.
     */
    private fun iconMask(icon: Drawable): Bitmap? {
        val adaptive = icon as? AdaptiveIconDrawable ?: return null
        val monochrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) adaptive.monochrome else null
        val layer = monochrome ?: adaptive.foreground ?: return null
        return maskOf(layer, ADAPTIVE_INSET)
    }

    /**
     * [drawable]'s ink as an `ALPHA_8` bitmap, cropped to it and [MASK_H] tall, read inside the
     * zone [inset] in from each edge; null when the ink covers too little or too much of the zone.
     */
    internal fun maskOf(
        drawable: Drawable,
        inset: Float,
    ): Bitmap? {
        val whole = Bitmap.createBitmap(READ, READ, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, READ, READ)
        drawable.draw(Canvas(whole))
        val from = (READ * inset).roundToInt()
        val side = READ - 2 * from
        val alpha = IntArray(side * side)
        whole.getPixels(alpha, 0, side, from, from, side, side)
        for (at in alpha.indices) alpha[at] = alpha[at] ushr ALPHA_SHIFT
        val box = inkBox(alpha, side) ?: return null
        val cover = alpha.count { it >= INK }.toFloat() / alpha.size
        if (cover !in MIN_COVER..MAX_COVER) return null
        val art = Bitmap.createBitmap(whole, from + box.left, from + box.top, box.width, box.height).extractAlpha()
        val width = (box.width * MASK_H.toFloat() / box.height).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(art, width, MASK_H, true)
    }

    /** The smallest box around the ink of a [side]-square of alpha values; null without ink. */
    private fun inkBox(
        alpha: IntArray,
        side: Int,
    ): Box? {
        var left = side
        var top = side
        var right = -1
        var bottom = -1
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (alpha[y * side + x] < INK) continue
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return if (right < 0) null else Box(left, top, right - left + 1, bottom - top + 1)
    }

    private class Box(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    /** Pixel art made from [mask]; see [pixelsFrom]. */
    private fun pixelsOf(mask: Bitmap): List<String> =
        pixelsFrom(mask.width, mask.height) { x, y -> mask.getPixel(x, y) ushr ALPHA_SHIFT }

    /**
     * Pixel art made from a picture [width] by [height], whose ink [alpha] gives at each pixel:
     * [SourceMark.MAX_H] rows, or fewer for a wide picture, and as many columns as keep its shape,
     * up to [SourceMark.MAX_W]. A cell is ink when half the points read in it are. Art with too few or too
     * many ink cells to tell apart is empty, so the letter stands in.
     */
    internal fun pixelsFrom(
        width: Int,
        height: Int,
        alpha: (Int, Int) -> Int,
    ): List<String> {
        if (width <= 0 || height <= 0) return emptyList()
        val wide = width.toFloat() / height
        // a picture wider than the art may be keeps its shape in fewer rows
        val rows = minOf(SourceMark.MAX_H.toFloat(), SourceMark.MAX_W / wide).roundToInt().coerceIn(1, SourceMark.MAX_H)
        val columns = (rows * wide).roundToInt().coerceIn(1, SourceMark.MAX_W)
        val grid = Grid(width, height, columns, rows, alpha)
        val art = List(rows) { row -> String(CharArray(columns) { column -> if (grid.ink(column, row)) '#' else '.' }) }
        val share = art.sumOf { row -> row.count { it == '#' } }.toFloat() / (art.size * columns)
        return if (share in MIN_INK..MAX_INK) art else emptyList()
    }

    /** A picture [width] by [height] read as [columns] by [rows] cells of pixel art. */
    private class Grid(
        val width: Int,
        val height: Int,
        val columns: Int,
        val rows: Int,
        val alpha: (Int, Int) -> Int,
    ) {
        /**
         * Whether the cell at [column], [row] is ink: half the points read in it are. The points are
         * the same in every cell, so a picture that mirrors makes art that does.
         */
        fun ink(
            column: Int,
            row: Int,
        ): Boolean {
            var ink = 0
            for (sy in 0 until SAMPLES) {
                for (sx in 0 until SAMPLES) {
                    val x = ((column + (sx + HALF) / SAMPLES) * width / columns).toInt().coerceIn(0, width - 1)
                    val y = ((row + (sy + HALF) / SAMPLES) * height / rows).toInt().coerceIn(0, height - 1)
                    if (alpha(x, y) >= INK) ink++
                }
            }
            return ink * 2 >= SAMPLES * SAMPLES
        }
    }

    /** How many points are read across a cell of made pixel art, and down it. */
    private const val SAMPLES = 4

    private const val HALF = 0.5f

    private const val ALPHA_SHIFT = 24
}
