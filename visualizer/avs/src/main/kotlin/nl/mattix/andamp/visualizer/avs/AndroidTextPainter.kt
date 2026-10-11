// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * Draws a preset's text with the phone's fonts. A face the phone does not
 * have falls back to its default, with the weight and slant kept. Letters are
 * not smoothed: the frame is small and is scaled up without smoothing too.
 */
internal class AndroidTextPainter : AvsTextPainter {
    private val paint = Paint().apply { color = Color.WHITE }

    override fun paint(
        text: String,
        font: AvsFont,
    ): AvsTextMask? {
        val style =
            when {
                font.bold && font.italic -> Typeface.BOLD_ITALIC
                font.bold -> Typeface.BOLD
                font.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
        paint.typeface = Typeface.create(font.face.ifEmpty { null }, style)
        paint.textSize = font.height.coerceIn(1, MAX_HEIGHT).toFloat()
        val metrics = paint.fontMetrics
        val width = ceil(paint.measureText(text)).toInt().coerceAtMost(MAX_WIDTH)
        val height = ceil(metrics.descent - metrics.ascent).toInt()
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, 0f, -metrics.ascent, paint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return AvsTextMask(width, height, BooleanArray(pixels.size) { Color.alpha(pixels[it]) >= HALF })
    }

    private companion object {
        const val MAX_HEIGHT = 512
        const val MAX_WIDTH = 4_096
        const val HALF = 128
    }
}
