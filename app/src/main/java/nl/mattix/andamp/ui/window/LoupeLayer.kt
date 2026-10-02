// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.zIndex
import nl.mattix.andamp.state.WinampState

/**
 * The lens, drawn over every window. It sits above the finger on a stalk and grows in on a
 * spring.
 *
 * Inside is the window's own drawing, redrawn at [Loupe.ZOOM], with a ring around the control
 * the crosshair is on, which is what a release presses.
 */
@Composable
internal fun LoupeLayer(
    state: WinampState,
    scale: Int,
) {
    val loupe = state.loupe ?: return
    val rect = state.windowRects[loupe.window] ?: return
    // a spring on the way in; on the way out the lens is removed at once
    var shown by remember(loupe) { mutableFloatStateOf(0f) }
    val grown by animateFloatAsState(
        targetValue = shown,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessHigh),
        label = "loupe",
    )
    LaunchedEffect(loupe) { shown = 1f }

    // every window carries a zIndex, so a later sibling without one would draw under them
    Canvas(
        Modifier
            .fillMaxSize()
            .zIndex(ABOVE_EVERY_WINDOW),
    ) {
        drawLoupe(loupe, rect, scale, grown)
    }
}

/** Where the lens sits and what is in it, given how far it has grown. */
private fun DrawScope.drawLoupe(
    loupe: Loupe,
    window: IntRect,
    scale: Int,
    grown: Float,
) {
    if (grown <= 0.01f) return
    val s = scale.toFloat()
    val focusX = (window.left + loupe.focus.x) * s
    val focusY = (window.top + loupe.focus.y) * s
    // the lens takes the shape of the cluster it shows: wide for a row of buttons, tall
    // for a column
    val lensW = (loupe.roam.width * Loupe.ZOOM).coerceIn(Loupe.LENS_MIN, Loupe.LENS_MAX) * s
    val lensH = (loupe.roam.height * Loupe.ZOOM).coerceIn(Loupe.LENS_MIN, Loupe.LENS_MAX) * s
    // kept inside the sides of the screen
    val centreX = focusX.coerceIn(lensW / 2 + MARGIN * s, size.width - lensW / 2 - MARGIN * s)
    // Above the finger by LIFT, or against the top edge when the screen has less room.
    // Only when that would leave it covering the finger does it go below.
    val wanted = focusY - Loupe.LIFT * s - lensH / 2
    val lifted = maxOf(wanted, MARGIN * s + lensH / 2)
    val overhead = lifted + lensH / 2 <= focusY - CLEARANCE * s
    val centreY = if (overhead) lifted else focusY + Loupe.LIFT * s + lensH / 2
    val lens =
        Rect(
            centreX - lensW / 2 * grown,
            centreY - lensH / 2 * grown,
            centreX + lensW / 2 * grown,
            centreY + lensH / 2 * grown,
        )
    val corner = CornerRadius(CORNER * s * grown, CORNER * s * grown)
    val shape = Path().apply { addRoundRect(RoundRect(lens, corner)) }

    stalk(focusX, focusY, centreX, if (overhead) lens.bottom else lens.top, s, grown)
    shadow(lens, corner, s, grown)

    clipPath(shape) {
        // the window's own draw block, magnified about the crosshair with the same
        // nearest-neighbor sprites
        withTransform({
            translate(
                centreX - (window.left + loupe.focus.x) * s * Loupe.ZOOM * grown,
                centreY - (window.top + loupe.focus.y) * s * Loupe.ZOOM * grown,
            )
            scale(s * Loupe.ZOOM * grown, s * Loupe.ZOOM * grown, pivot = Offset.Zero)
            translate(window.left.toFloat(), window.top.toFloat())
        }) {
            loupe.paint(this)
        }
        // darkens toward the rim
        drawRect(
            brush =
                androidx.compose.ui.graphics.Brush.radialGradient(
                    0.6f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.28f * grown),
                    center = lens.center,
                    radius = lensW / 2,
                ),
            topLeft = lens.topLeft,
            size = lens.size,
        )
        ring(loupe, window, centreX, centreY, s, grown)
    }
    // the rim: a dark line for the edge and a light one just inside it
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.55f * grown),
        topLeft = lens.topLeft,
        size = lens.size,
        cornerRadius = corner,
        style = Stroke(width = RIM * s),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.35f * grown),
        topLeft = Offset(lens.left + RIM * s, lens.top + RIM * s),
        size = Size(lens.width - RIM * 2 * s, lens.height - RIM * 2 * s),
        cornerRadius = corner,
        style = Stroke(width = s),
    )
}

/** The ring around the button a release would press, in the lens's own space. */
private fun DrawScope.ring(
    loupe: Loupe,
    window: IntRect,
    centreX: Float,
    centreY: Float,
    s: Float,
    grown: Float,
) {
    val target = loupe.target?.takeIf { it.id != Loupe.chromeOf(loupe.window) } ?: return
    val zoom = s * Loupe.ZOOM * grown
    val left = centreX + (target.bounds.left - loupe.focus.x) * zoom
    val top = centreY + (target.bounds.top - loupe.focus.y) * zoom
    val size = Size(target.bounds.width * zoom, target.bounds.height * zoom)
    val pad = 2 * s
    drawRoundRect(
        color = Color.White.copy(alpha = 0.9f * grown),
        topLeft = Offset(left - pad, top - pad),
        size = Size(size.width + pad * 2, size.height + pad * 2),
        cornerRadius = CornerRadius(pad, pad),
        style = Stroke(width = 1.5f * s),
    )
}

/**
 * The line from the finger to the lens, widening toward the lens.
 */
private fun DrawScope.stalk(
    focusX: Float,
    focusY: Float,
    centreX: Float,
    lensBottom: Float,
    s: Float,
    grown: Float,
) {
    val half = STALK * s * grown
    val path =
        Path().apply {
            moveTo(focusX - half / 2, focusY)
            lineTo(focusX + half / 2, focusY)
            lineTo(centreX + half, lensBottom)
            lineTo(centreX - half, lensBottom)
            close()
        }
    drawPath(path, Color.Black.copy(alpha = 0.22f * grown))
    drawCircle(Color.White.copy(alpha = 0.8f * grown), radius = 2f * s * grown, center = Offset(focusX, focusY))
}

/** A soft drop shadow built from a few translucent rounded rects. */
private fun DrawScope.shadow(
    lens: Rect,
    corner: CornerRadius,
    s: Float,
    grown: Float,
) {
    repeat(SHADOW_STEPS) { step ->
        val spread = (step + 1) * s
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.06f * grown),
            topLeft = Offset(lens.left - spread, lens.top - spread + spread),
            size = Size(lens.width + spread * 2, lens.height + spread * 2),
            cornerRadius = CornerRadius(corner.x + spread, corner.y + spread),
        )
    }
}

/** In virtual pixels. */
private const val CORNER = 6f
private const val RIM = 1.5f
private const val MARGIN = 6f

/** The gap, in virtual pixels, the lens must leave above the finger to count as above it. */
private const val CLEARANCE = 8f
private const val STALK = 5f
private const val SHADOW_STEPS = 4

/** Higher than any window's own z-index. */
private const val ABOVE_EVERY_WINDOW = 1000f
