// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Draws the status bar's icons dark over something light, and light over something dark.
 *
 * The bar is transparent. Over the floating player it sits on the wallpaper, so the answer
 * is read from the wallpaper and read again when the wallpaper changes. A player that fills
 * the screen ([playerFillsScreen]) puts black under it, and under the navigation bar too.
 * Over any other screen it sits on that screen's own background, and [dark] is the answer.
 */
@Composable
fun StatusBarIcons(
    onPlayer: Boolean,
    playerFillsScreen: Boolean,
    dark: Boolean,
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val lightWallpaper = rememberWallpaperIsLight()
    val wanted = statusBarIconsDark(onPlayer, playerFillsScreen, lightWallpaper, lightScreen = dark)
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = wanted
    }
    NavigationBarOverBlack(onPlayer && playerFillsScreen)
}

/**
 * While [active], shows the navigation bar's buttons light and without the scrim the system
 * draws behind them for contrast: what is under the bar is black. The bar is given back as it
 * was found.
 *
 * From Android 10, where that scrim can be switched off. Before it the bar has a color of
 * its own, and is left as it is.
 */
@Composable
private fun NavigationBarOverBlack(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val window = (view.context as? Activity)?.window
        if (!active || window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@DisposableEffect onDispose {}
        }
        val bars = WindowInsetsControllerCompat(window, view)
        val darkButtons = bars.isAppearanceLightNavigationBars
        val scrim = window.isNavigationBarContrastEnforced
        bars.isAppearanceLightNavigationBars = false
        window.isNavigationBarContrastEnforced = false
        onDispose {
            bars.isAppearanceLightNavigationBars = darkButtons
            window.isNavigationBarContrastEnforced = scrim
        }
    }
}

/** Whether the status bar's icons are dark, for what is under the bar. */
internal fun statusBarIconsDark(
    onPlayer: Boolean,
    playerFillsScreen: Boolean,
    lightWallpaper: Boolean,
    lightScreen: Boolean,
): Boolean =
    when {
        !onPlayer -> lightScreen
        playerFillsScreen -> false
        else -> lightWallpaper
    }

/** Whether the home screen's wallpaper wants dark text over it, kept up to date. */
@Composable
private fun rememberWallpaperIsLight(): Boolean {
    val context = LocalContext.current
    var light by remember(context) { mutableStateOf(wallpaperIsLight(context)) }
    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return@DisposableEffect onDispose {}
        val wallpapers = WallpaperManager.getInstance(context)
        val listener =
            WallpaperManager.OnColorsChangedListener { _, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) light = wallpaperIsLight(context)
            }
        wallpapers.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wallpapers.removeOnColorsChangedListener(listener) }
    }
    return light
}

/**
 * The system's dark-text hint from Android 12, the luminance of the wallpaper's primary
 * color on Android 8.1 to 11, and false (light icons) below 8.1 or when the system reports
 * no colors.
 */
private fun wallpaperIsLight(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return false
    val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    } else {
        Color(colors.primaryColor.toArgb()).luminance() > LIGHT
    }
}

/** The luminance above which a wallpaper counts as light. */
private const val LIGHT = 0.5f
