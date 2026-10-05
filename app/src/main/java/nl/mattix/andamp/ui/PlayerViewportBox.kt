// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import nl.mattix.andamp.ui.window.LocalSurfaceScreen
import nl.mattix.andamp.ui.window.SurfaceScreen
import nl.mattix.andamp.ui.window.shadeStripBelowStatusBar

/**
 * The box the player's windows are laid out in, filling the space it is given.
 *
 * [content] is measured on [viewport]'s surface and sees that surface's size as its
 * constraints. A surface larger than the screen is drawn through one layer that shrinks it
 * to fit, and touches are mapped back through that layer. What [content] is told about the
 * screen through [LocalSurfaceScreen] is in the surface's pixels too.
 *
 * Only the skin belongs in here. A menu or a dialog opens in a window of its own, at the
 * screen's size, so it is composed outside and placed by [PlayerViewport.shrink].
 */
@Composable
internal fun PlayerViewportBox(
    viewport: PlayerViewport,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val screen = surfaceScreenOn(viewport, LocalSurfaceScreen.current)
    BoxWithConstraints(modifier.fillMaxSize().shrunkToFit(viewport)) {
        CompositionLocalProvider(LocalSurfaceScreen provides screen) { content() }
    }
}

/**
 * Measures what follows at [viewport]'s size and draws it at the size it was given.
 *
 * The layer is rendered off screen at the surface's size first, so the shrinking samples
 * the finished picture rather than each sprite.
 */
private fun Modifier.shrunkToFit(viewport: PlayerViewport): Modifier {
    if (viewport.shrink == 1f) return this
    return layout { measurable, constraints ->
        val surface =
            measurable.measure(
                Constraints.fixed(viewport.inner(constraints.maxWidth), viewport.inner(constraints.maxHeight)),
            )
        layout(constraints.maxWidth, constraints.maxHeight) {
            surface.placeWithLayer(0, 0) {
                scaleX = viewport.shrink
                scaleY = viewport.shrink
                transformOrigin = TransformOrigin(0f, 0f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
        }
    }
}

/**
 * What the windows on [viewport]'s surface are told about the screen.
 *
 * A surface the size of the screen passes [told] on, null included: its windows read the
 * same insets this would. A larger one needs every inset in its own pixels, so the window's
 * insets are read here when nothing was told.
 */
@Composable
private fun surfaceScreenOn(
    viewport: PlayerViewport,
    told: SurfaceScreen?,
): SurfaceScreen? {
    if (viewport.shrink == 1f) return told
    val density = LocalDensity.current
    val screen =
        told ?: SurfaceScreen(
            statusBar = WindowInsets.statusBars.getTop(density),
            shadeStrip = shadeStripBelowStatusBar(density),
            bottom = WindowInsets.safeDrawing.getBottom(density),
        )
    return SurfaceScreen(
        statusBar = viewport.inner(screen.statusBar),
        shadeStrip = viewport.inner(screen.shadeStrip),
        bottom = viewport.inner(screen.bottom),
    )
}
