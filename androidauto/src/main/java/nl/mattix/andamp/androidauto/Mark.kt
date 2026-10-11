// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/**
 * The app's mark, for the button Android Auto requires on the screen.
 *
 * Android Auto colors a button's icon in one color. Given the app's whole icon, which is what
 * `Action.APP_ICON` stands for, that comes out as a plain disc. So the button gets the icon's
 * single-color layer, the one a themed home screen uses, as a picture to color; an icon
 * without that layer gives its foreground.
 */
internal object Mark {
    /** The mark as an icon for a car, or null when the app's icon has no layers to take it from. */
    fun of(context: Context): CarIcon? {
        val icon =
            runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull()
                as? AdaptiveIconDrawable ?: return null
        val layer = singleColorOf(icon) ?: icon.foreground ?: return null
        return CarIcon
            .Builder(IconCompat.createWithBitmap(pictureOf(layer)))
            .setTint(CarColor.DEFAULT)
            .build()
    }

    private fun singleColorOf(icon: AdaptiveIconDrawable): Drawable? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) icon.monochrome else null

    /**
     * The part of [layer] an icon shows, as a picture. A layer is half again as wide and as
     * high as that part, which sits in its middle.
     */
    private fun pictureOf(layer: Drawable): Bitmap {
        val picture = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        // a copy, so the icon the rest of the app draws keeps its own bounds
        val drawn = layer.constantState?.newDrawable()?.mutate() ?: layer
        val around = SIZE / 4
        drawn.setBounds(-around, -around, SIZE + around, SIZE + around)
        drawn.draw(Canvas(picture))
        return picture
    }

    /** The picture's width and height, in pixels. */
    private const val SIZE = 128
}
