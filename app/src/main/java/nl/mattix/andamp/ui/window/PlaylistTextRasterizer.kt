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
import nl.mattix.andamp.state.MarkPlace
import nl.mattix.andamp.state.SourceForRows
import nl.mattix.andamp.state.SourceMark
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
                s.listMarks,
                s.sourceMarks,
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
        val before = s.listMarks == MarkPlace.BEFORE
        val pixels = glyphs?.takeIf { spelled.isNotEmpty() }?.let { pixelRows(spelled, it, layout, before) }
        val bitmap =
            if (pixels != null && outlined.isEmpty()) {
                pixels
            } else {
                outlineRows(outlined, layout, scale, style, under = pixels, before = before)
            }

        return bitmap.asImageBitmap().also {
            cache = it
            cacheKey = key
        }
    }

    /**
     * One row in view: where its top is in the layer, what it says (its [number], its [title]
     * and its [duration]), in which color, and the mark of its source when the playlist
     * shows them.
     */
    private class Row(
        val top: Int,
        val number: String,
        val title: String,
        val duration: String,
        val argb: Int,
        val mark: SourceMark?,
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
                        number = "${index + 1}. ",
                        title = s.rowLabel(track, index),
                        duration = formatTime(track.durationSec),
                        argb = (if (index == s.currentIndex) style.current else style.normal).toArgb(),
                        mark = if (s.listMarks == MarkPlace.OFF) null else s.sourceMarks[SourceForRows.of(track).id],
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
        before: Boolean,
    ): Bitmap {
        val width = layout.textRight - layout.textLeft
        val height = layout.middleH
        // the bitmap made below copies the block, so the next layer can write over it
        if (block.size == width * height) block.fill(0) else block = IntArray(width * height)
        for (row in rows) {
            // a glyph is five pixels of letter over one of nothing: the letter is centered
            val y = row.top + (layout.rowH - BitmapFont.CHAR_H + 1) / 2
            writeRow(glyphs, row.title, row.duration, row.argb, y, block, width, row.number, row.mark, before)
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
        before: Boolean,
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

            var titleLeft = ListText.LEFT_INSET
            var titleRight = durX - DURATION_GAP
            val marks = listOfNotNull(row.mark)
            if (marks.isNotEmpty()) {
                val markW = MarkInk.outlineWidth(marks, paint)
                if (before) {
                    canvas.drawText(row.number, titleLeft, baseline, paint)
                    titleLeft += paint.measureText(row.number)
                    MarkInk.outline(marks, titleLeft, top, layout.rowH, canvas, paint)
                    titleLeft += markW + MARK_GAP
                } else {
                    MarkInk.outline(marks, titleRight - markW, top, layout.rowH, canvas, paint)
                    titleRight -= markW + MARK_GAP
                }
            }
            val words = if (marks.isNotEmpty() && before) row.title else row.number + row.title
            canvas.save()
            canvas.clipRect(0f, top, titleRight, top + layout.rowH)
            canvas.drawText(words, titleLeft, baseline, paint)
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
         * Writes one row in the skin's font into [block], [width] pixels wide: [number] and
         * [title] from the left and [duration] against the right, in [argb], the letters' tops
         * at [y]. A title too long for the row ends on a whole letter and the font's ellipsis.
         * [mark], when given, is the row's source's: between the number and the title when
         * [before], else in front of the duration.
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
            number: String = "",
            mark: SourceMark? = null,
            before: Boolean = false,
        ) {
            var left = ListText.LEFT_INSET.toInt()
            if (number.isNotEmpty()) {
                glyphs.write(BitmapFont.normalize(number), left, y, argb, block, width)
                left += number.length * BitmapFont.CHAR_W
            }
            val durX = width - ListText.RIGHT_INSET.toInt() - duration.length * BitmapFont.CHAR_W
            glyphs.write(duration, durX, y, argb, block, width)
            var right = durX - DURATION_GAP
            if (mark != null) {
                val marks = listOf(mark)
                val markW = MarkInk.pixelWidth(marks)
                if (before) {
                    MarkInk.spell(marks, left, y, argb, glyphs, block, width)
                    left += markW + MARK_GAP
                } else {
                    MarkInk.spell(marks, right - markW, y, argb, glyphs, block, width)
                    right -= markW + MARK_GAP
                }
            }
            val room = (right - left) / BitmapFont.CHAR_W
            glyphs.write(BitmapFont.fitted(title, room), left, y, argb, block, width)
        }

        /** The room between a source's mark and the words beside it. */
        private const val MARK_GAP = 3

        /** The title stops this far short of the duration. */
        private const val DURATION_GAP = 4

        /** Skin art is scaled without filtering, so a pixel stays a square. */
        private val UNFILTERED = Paint().apply { isFilterBitmap = false }
    }
}
