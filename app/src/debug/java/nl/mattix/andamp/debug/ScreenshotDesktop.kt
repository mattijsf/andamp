// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.debug

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes

/**
 * Debug builds only: a bare home screen to show behind the player.
 *
 *     adb shell am start -n nl.mattix.andamp/nl.mattix.andamp.debug.ScreenshotDesktop
 *
 * It shows the wallpaper, Andamp's own icon where the first cell of the grid is, and a search bar
 * along the bottom above the gesture handle. The player is translucent, so this is what shows
 * around it.
 *
 * The icon is the installed one, in the shape the system masks every icon to. The search bar is
 * drawn here in the phone's own palette, with a plain magnifier.
 */
class ScreenshotDesktop : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(Desktop(this))
    }
}

private class Desktop(
    context: Context,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val icon = context.packageManager.getApplicationIcon(context.packageName)
    private val name = context.applicationInfo.loadLabel(context.packageManager).toString()
    private val night =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private val label =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sp(LABEL_SP)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(2 * density, 0f, density, SHADOW)
        }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette(BAR_LIGHT, BAR_DARK) }
    private val ink =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette(INK_LIGHT, INK_DARK)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
        }
    private val hint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette(INK_LIGHT, INK_DARK)
            textSize = sp(HINT_SP)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /** A role from the system's own palette where it has one, a plain gray where it does not. */
    private fun palette(
        light: Tone,
        dark: Tone,
    ): Int {
        val tone = if (night) dark else light
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getColor(tone.role) else tone.fallback
    }

    @Suppress("DEPRECATION") // the replacement is API 30, and this has to run wherever the app does
    override fun onDraw(canvas: Canvas) {
        val insets = rootWindowInsets
        val top = insets?.systemWindowInsetTop ?: 0
        val bottom = insets?.systemWindowInsetBottom ?: 0

        // the first cell of the grid, where a launcher puts the first icon: four
        // columns on a phone, six on a tablet or a desktop, as launchers do
        val large = width / density >= LARGE_SCREEN_DP
        val cell = width / if (large) LARGE_COLUMNS else COLUMNS
        val size = ICON_DP * density
        val x = cell / 2f
        val y = top + ROW_TOP_DP * density
        icon.setBounds((x - size / 2).toInt(), y.toInt(), (x + size / 2).toInt(), (y + size).toInt())
        icon.draw(canvas)
        canvas.drawText(name, x, y + size + LABEL_GAP_DP * density - label.ascent(), label)

        // the search bar, a pill across the bottom above the gesture handle
        val margin = BAR_MARGIN_DP * density
        val height = BAR_DP * density
        val lowest = this.height - bottom - BAR_BOTTOM_DP * density
        // across a phone; on a wide screen no wider than a launcher lets it be, and centered
        val across = minOf(width - 2 * margin, BAR_MAX_DP * density)
        val pill = RectF((width - across) / 2, lowest - height, (width + across) / 2, lowest)
        canvas.drawRoundRect(pill, height / 2, height / 2, bar)
        val glass = GLASS_DP * density
        val cx = pill.left + height * 0.55f
        val cy = pill.centerY() - glass * 0.12f
        canvas.drawCircle(cx, cy, glass * 0.34f, ink)
        canvas.drawLine(cx + glass * 0.24f, cy + glass * 0.24f, cx + glass * 0.5f, cy + glass * 0.5f, ink)
        canvas.drawText(SEARCH, cx + glass, pill.centerY() - (hint.ascent() + hint.descent()) / 2, hint)
    }

    private companion object {
        const val COLUMNS = 4
        const val LARGE_COLUMNS = 6
        const val LARGE_SCREEN_DP = 600
        const val BAR_MAX_DP = 640f
        const val ICON_DP = 56f
        const val ROW_TOP_DP = 16f
        const val LABEL_GAP_DP = 6f
        const val LABEL_SP = 13f
        const val BAR_DP = 54f
        const val BAR_MARGIN_DP = 20f
        const val BAR_BOTTOM_DP = 20f
        const val GLASS_DP = 22f
        const val HINT_SP = 16f
        const val SEARCH = "Search"
        val SHADOW = Color.argb(0x66, 0, 0, 0)

        // the pill in the palette's surface tones, and what is drawn on it
        val BAR_LIGHT = Tone(android.R.color.system_neutral1_50, Color.rgb(0xF1, 0xF3, 0xF4))
        val BAR_DARK = Tone(android.R.color.system_neutral1_800, Color.rgb(0x30, 0x31, 0x34))
        val INK_LIGHT = Tone(android.R.color.system_neutral2_700, Color.rgb(0x44, 0x47, 0x4A))
        val INK_DARK = Tone(android.R.color.system_neutral2_200, Color.rgb(0xC4, 0xC7, 0xCA))
    }
}

/** A role in the system's palette, and the color to use where there is no palette to ask. */
private class Tone(
    @param:ColorRes val role: Int,
    @param:ColorInt val fallback: Int,
)
