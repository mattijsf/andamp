// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue

/** How much room the picture has in the cell a launcher gave it. */
internal object WidgetBox {
    /**
     * The box the picture has in the current orientation.
     *
     * The four size options are two boxes: MIN_WIDTH with MAX_HEIGHT is the portrait cell,
     * MAX_WIDTH with MIN_HEIGHT the landscape one. A host swaps between them on rotation without
     * changing the bundle, so onAppWidgetOptionsChanged does not fire.
     *
     * So this reads the current orientation. Taking the smaller of each pair would fit either way
     * round, but draws the player much smaller than the cell allows. After a rotation on a home
     * screen that rotates, the picture is the other orientation's until the next update.
     *
     * A zero means the host left that option unset, so the other half of the pair stands in for it.
     */
    fun of(
        context: Context,
        options: Bundle,
    ): Pair<Int, Int> {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        fun side(
            now: String,
            other: String,
        ) = options.getInt(now).takeIf { it > 0 } ?: options.getInt(other)
        val width =
            px(
                context,
                if (landscape) {
                    side(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                } else {
                    side(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                },
            )
        val height =
            px(
                context,
                if (landscape) {
                    side(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                } else {
                    side(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                },
            )
        return width to height
    }

    fun px(
        context: Context,
        dp: Int,
    ) = TypedValue
        .applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics)
        .toInt()
}
