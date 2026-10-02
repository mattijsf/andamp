// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.rowLabel

/**
 * Renders the playlist rows: Liberation Sans, which is metric-compatible with Arial, laid out
 * in virtual coordinates and rasterized at device resolution with antialiasing.
 *
 * [ListText.typefaceFor] maps the font PLEDIT.TXT names to a typeface. The layer is cached
 * and rebuilt only when its inputs change.
 */
class PlaylistTextRasterizer(
    private val arialLike: Typeface,
) {
    private var cacheKey: List<Any?>? = null
    private var cache: ImageBitmap? = null

    fun textLayer(
        s: WinampState,
        layout: PlaylistLayout,
        style: PleditStyle,
        scale: Int,
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
                scale,
            )
        cache?.let { if (key == cacheKey) return it }

        val width = layout.textRight - layout.textLeft
        val bitmap = Bitmap.createBitmap(width * scale, layout.middleH * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat()) // layout in virtual px, rasterize at device px
        val paint = ListText.paint(ListText.typefaceFor(style.fontName, arialLike))

        for (visRow in 0..layout.visibleRows) {
            val index = s.playlistScroll + visRow
            val track = s.playlist.getOrNull(index) ?: break
            val rowTop = ListText.TOP_PAD + visRow * Dest.PL_ROW_H
            val baseline = ListText.baseline(paint, rowTop, Dest.PL_ROW_H)
            paint.color = (if (index == s.currentIndex) style.current else style.normal).toArgb()

            val duration = formatTime(track.durationSec)
            val durWidth = paint.measureText(duration)
            val durX = width - ListText.RIGHT_INSET - durWidth
            canvas.drawText(duration, durX, baseline, paint)

            canvas.save()
            canvas.clipRect(0f, rowTop, durX - 4f, rowTop + Dest.PL_ROW_H)
            canvas.drawText("${index + 1}. ${s.rowLabel(track, index)}", 2f, baseline, paint)
            canvas.restore()
        }

        return bitmap.asImageBitmap().also {
            cache = it
            cacheKey = key
        }
    }

    companion object {
        const val ASSET_PATH = "fonts/LiberationSans-Regular.ttf"
    }
}
