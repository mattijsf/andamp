// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density

/**
 * What the surface being drawn knows about the screen around it, in the surface's own
 * pixels, or null to read the window's own insets.
 *
 * The activity reads its insets, because its window is the screen. The floating player's
 * insets describe its own window, which moves with the windows drawn inside it, so reading
 * them feeds back into the layout. The overlay measures the display from outside the
 * composition and provides the answer here.
 */
data class SurfaceScreen(
    /** The status bar's height: how far down this surface's content begins. */
    val statusBar: Int,
    /** How much of the notification shade's gesture strip lies inside the surface. */
    val shadeStrip: Int,
    /** What the bottom of the screen keeps for the system's own gestures. */
    val bottom: Int,
)

val LocalSurfaceScreen = compositionLocalOf<SurfaceScreen?> { null }

/**
 * The first row a window's handle may occupy: below the strip the system keeps for the
 * notification shade, where a drag pulls the shade down.
 *
 * The screen is already padded by the status bar, so what is left of the strip is the
 * difference between the two, unless [LocalSurfaceScreen] says how much of the strip is
 * inside the surface.
 */
@Composable
internal fun grabbableTop(
    scale: Int,
    density: Density,
): Int {
    LocalSurfaceScreen.current?.let { return it.shadeStrip / scale }
    return shadeStripBelowStatusBar(density) / scale
}

/**
 * What the bottom of the surface keeps for the system's own gestures and the keyboard, in the
 * surface's pixels: what [LocalSurfaceScreen] says, or the window's own inset.
 */
@Composable
internal fun surfaceBottomInset(density: Density): Int =
    LocalSurfaceScreen.current?.bottom ?: WindowInsets.safeDrawing.getBottom(density)

/** How much of the notification shade's gesture strip the window's insets leave below the status bar. */
@Composable
internal fun shadeStripBelowStatusBar(density: Density): Int {
    val shade = WindowInsets.mandatorySystemGestures.getTop(density)
    val status = WindowInsets.statusBars.getTop(density)
    return (shade - status).coerceAtLeast(0)
}
