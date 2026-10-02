// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

/**
 * The phone's own palette, when it has one.
 *
 * Not a composable: the home screen widget draws from a broadcast with no composition and has to
 * use the same colors the player does. Null below Android 12, where there is no dynamic color; a
 * live skin then falls back to the file it ships.
 */
object SystemPalette {
    fun of(
        context: Context,
        dark: Boolean,
    ): ColorScheme? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val current = current(context)
            if (dark) dynamicDarkColorScheme(current) else dynamicLightColorScheme(current)
        } else {
            null
        }

    /**
     * [context], reading its colors through the package's current resources.
     *
     * The palette reaches an app as overlays the system adds to the package. A process started just
     * after install, before the overlays are applied, resolves the platform's default colors from
     * its own resources and is not told when the overlays land. Resources fetched afresh from the
     * package manager have them.
     */
    private fun current(context: Context): Context =
        runCatching {
            val manager = context.packageManager

            @Suppress("DEPRECATION") // the flags overload is API 33; 0 means the same on every level
            val info = manager.getApplicationInfo(context.packageName, 0)
            Current(context, manager.getResourcesForApplication(info))
        }.getOrDefault(context) // falls back to the process's own resources

    private class Current(
        base: Context,
        private val current: Resources,
    ) : ContextWrapper(base) {
        override fun getResources(): Resources = current
    }
}
