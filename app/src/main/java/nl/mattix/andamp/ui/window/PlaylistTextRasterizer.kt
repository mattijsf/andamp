// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.GlyphMask
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.rowLabel

/**
 * Renders the playlist rows in one of two fonts.
 *
 * The outline font is Liberation Sans, which is metric-compatible with Arial, laid out in
 * virtual coordinates and rasterized at device resolution with antialiasing.
 * [ListText.typefaceFor] maps the font PLEDIT.TXT names to a typeface.
 *
 * The skin's font is the 5x6 one on its TEXT.BMP, in the colors PLEDIT.TXT gives the rows.
 * Its layer is one pixel to the virtual pixel, written straight into a pixel block, and the
 * window draws it at its scale unfiltered. A title too long for its row ends in the font's
 * ellipsis. A row whose title the skin's font cannot spell keeps the outline font.
 *
 * Only the rows in view are drawn, so a layer costs the same whatever the length of the
 * list. It is cached and rebuilt only when its inputs change.
 */
class PlaylistTextRasterizer(
    private val arialLike: Typeface,
) {
    private var cacheKey: List<Any?>? = null
    private var cache: ImageBitmap? = null

    private var maskSheet: ImageBitmap? = null
    private var mask: GlyphMask? = null

    /** The pixel block the skin's font is written into, kept between layers of one size. */
    private var block = IntArray(0)

    /**
     * The rows of [s] that show in [layout], as a layer the size of the text column.
     *
     * [skinFont] is the skin's text sheet when the rows are drawn in its font, and null for
     * the outline font. The layer is [scale] device pixels to the virtual pixel, or one when
     * every row in it is in the skin's font.
     */
    fun textLayer(
        s: WinampState,
        layout: PlaylistLayout,
        style: PleditStyle,
        scale: Int,
        skinFont: ImageBitmap? = null,
    ): ImageBitmap {
        // s.connecting and s.reach are in the key: they change what a row is called
        // without changing the playlist
        val key =
            listOf(
                s.playlist,
                s.playlistScroll,
                s.selectedRows,
                s.currentIndex,
                s.connecting,
                s.reach,
                style,
                // the bitmap is as wide as the text column and as tall as the rows
                layout.textRight - layout.textLeft,
                layout.height,
                layout.rowH,
                scale,
                skinFont,
            )
        cache?.let { if (key == cacheKey) return it }

        val rows = rowsIn(s, layout, style)
        val glyphs = skinFont?.let(::maskOf)
        val (spelled, outlined) =
            if (glyphs == null) emptyList<Row>() to rows else rows.partition { BitmapFont.covers(it.title) }
        val pixels = glyphs?.takeIf { spelled.isNotEmpty() }?.let { pixelRows(spelled, it, layout) }
        val bitmap =
            if (pixels != null && outlined.isEmpty()) {
                pixels
            } else {
                outlineRows(outlined, layout, scale, style, under = pixels)
            }

        return bitmap.asImageBitmap().also {
            cache = it
            cacheKey = key
        }
    }

    /** One row in view: where its top is in the layer, what it says and in which color. */
    private class Row(
        val top: Int,
        val title: String,
        val duration: String,
        val argb: Int,
    )

    private fun rowsIn(
        s: WinampState,
        layout: PlaylistLayout,
        style: PleditStyle,
    ): List<Row> =
        buildList {
            for (visRow in 0..layout.visibleRows) {
                val index = s.playlistScroll + visRow
                val track = s.playlist.getOrNull(index) ?: break
                add(
                    Row(
                        top = ListText.TOP_PAD.toInt() + visRow * layout.rowH,
                        title = "${index + 1}. ${s.rowLabel(track, index)}",
                        duration = formatTime(track.durationSec),
                        argb = (if (index == s.currentIndex) style.current else style.normal).toArgb(),
                    ),
                )
            }
        }

    /** The mask of [sheet], made once for as long as the skin is worn. */
    private fun maskOf(sheet: ImageBitmap): GlyphMask? {
        if (sheet !== maskSheet) {
            mask = glyphMaskOf(sheet)
            maskSheet = sheet
        }
        return mask
    }

    /** [rows] in the skin's font, one pixel to the virtual pixel. */
    private fun pixelRows(
        rows: List<Row>,
        glyphs: GlyphMask,
        layout: PlaylistLayout,
    ): Bitmap {
        val width = layout.textRight - layout.textLeft
        val height = layout.middleH
        // the bitmap made below copies the block, so the next layer can write over it
        if (block.size == width * height) block.fill(0) else block = IntArray(width * height)
        for (row in rows) {
            // a glyph is five pixels of letter over one of nothing: the letter is centered
            val y = row.top + (layout.rowH - BitmapFont.CHAR_H + 1) / 2
            writeRow(glyphs, row.title, row.duration, row.argb, y, block, width)
        }
        return Bitmap.createBitmap(block, width, height, Bitmap.Config.ARGB_8888)
    }

    /** [rows] in the outline font at device resolution, over the skin-font rows in [under], if any. */
    private fun outlineRows(
        rows: List<Row>,
        layout: PlaylistLayout,
        scale: Int,
        style: PleditStyle,
        under: Bitmap?,
    ): Bitmap {
        val width = layout.textRight - layout.textLeft
        val bitmap = Bitmap.createBitmap(width * scale, layout.middleH * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat()) // layout in virtual px, rasterize at device px
        under?.let { canvas.drawBitmap(it, 0f, 0f, UNFILTERED) }
        val paint = ListText.paint(ListText.typefaceFor(style.fontName, arialLike))
        for (row in rows) {
            val top = row.top.toFloat()
            val baseline = ListText.baseline(paint, top, layout.rowH)
            paint.color = row.argb

            val durX = width - ListText.RIGHT_INSET - paint.measureText(row.duration)
            canvas.drawText(row.duration, durX, baseline, paint)

            canvas.save()
            canvas.clipRect(0f, top, durX - DURATION_GAP, top + layout.rowH)
            canvas.drawText(row.title, ListText.LEFT_INSET, baseline, paint)
            canvas.restore()
        }
        return bitmap
    }

    companion object {
        const val ASSET_PATH = "fonts/LiberationSans-Regular.ttf"

        /** The ink of the skin's font on [sheet], its TEXT.BMP; null as [GlyphMask.of] says. */
        fun glyphMaskOf(sheet: ImageBitmap): GlyphMask? {
            val pixels = IntArray(sheet.width * sheet.height)
            sheet.asAndroidBitmap().getPixels(pixels, 0, sheet.width, 0, 0, sheet.width, sheet.height)
            return GlyphMask.of(pixels, sheet.width, sheet.height)
        }

        /**
         * Writes one row in the skin's font into [block], [width] pixels wide: [title] from
         * the left and [duration] against the right, in [argb], the letters' tops at [y]. A
         * title too long for the row ends on a whole letter and the font's ellipsis.
         */
        @Suppress("LongParameterList") // a row's words and color, and where they go
        fun writeRow(
            glyphs: GlyphMask,
            title: String,
            duration: String,
            argb: Int,
            y: Int,
            block: IntArray,
            width: Int,
        ) {
            val left = ListText.LEFT_INSET.toInt()
            val durX = width - ListText.RIGHT_INSET.toInt() - duration.length * BitmapFont.CHAR_W
            glyphs.write(duration, durX, y, argb, block, width)
            val room = (durX - DURATION_GAP - left) / BitmapFont.CHAR_W
            glyphs.write(BitmapFont.fitted(title, room), left, y, argb, block, width)
        }

        /** The title stops this far short of the duration. */
        private const val DURATION_GAP = 4

        /** Skin art is scaled without filtering, so a pixel stays a square. */
        private val UNFILTERED = Paint().apply { isFilterBitmap = false }
    }
}
