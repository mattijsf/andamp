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
import androidx.compose.ui.unit.Density
import nl.mattix.andamp.ui.window.LocalSurfaceScreen
import nl.mattix.andamp.ui.window.SurfaceScreen
import nl.mattix.andamp.ui.window.shadeStripBelowStatusBar

/**
 * The box the player's windows are laid out in, filling the space it is given.
 *
 * [content] is measured on [viewport]'s surface and sees that surface's size as its
 * constraints. A surface larger than the screen is drawn through one layer that shrinks it
 * to fit, and touches are mapped back through that layer. What [content] is told about the
 * screen through [LocalSurfaceScreen], and how many pixels make a dp through [LocalDensity],
 * are the surface's too.
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
    // one call for both, so switching between them keeps what the windows remember
    val shrunk = viewport.shrink != 1f
    val screen = if (shrunk) windowScreenOn(viewport) else LocalSurfaceScreen.current
    // a dp spans more of a larger surface's pixels than of the screen's
    val density = LocalDensity.current.let { if (shrunk) Density(it.density / viewport.shrink, it.fontScale) else it }
    BoxWithConstraints(modifier.fillMaxSize().then(if (shrunk) Modifier.shrunkToFit(viewport) else Modifier)) {
        CompositionLocalProvider(LocalSurfaceScreen provides screen, LocalDensity provides density) { content() }
    }
}

/**
 * Measures what follows at [viewport]'s size and draws it at the size it was given.
 *
 * The layer is rendered off screen at the surface's size first, so the shrinking samples
 * the finished picture rather than each sprite.
 */
private fun Modifier.shrunkToFit(viewport: PlayerViewport): Modifier =
    layout { measurable, constraints ->
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

/**
 * What the windows on a shrunk [viewport]'s surface are told about the screen: the window's
 * insets, in the surface's pixels.
 */
@Composable
private fun windowScreenOn(viewport: PlayerViewport): SurfaceScreen {
    val density = LocalDensity.current
    return SurfaceScreen(
        statusBar = viewport.inner(WindowInsets.statusBars.getTop(density)),
        shadeStrip = viewport.inner(shadeStripBelowStatusBar(density)),
        bottom = viewport.inner(WindowInsets.safeDrawing.getBottom(density)),
    )
}
