// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.ui.window.LensAim
import nl.mattix.andamp.ui.window.Loupe
import nl.mattix.andamp.ui.window.drawLens
import nl.mattix.walkit.TouchControl

/**
 * The magnifier over a skin's small controls, as [WalTouch] opens it on a hold: its crosshair
 * ([aim]) steers the skin's press, which the skin draws pressed or lit wherever it goes, and the
 * release lets go where the crosshair is. Between two controls the press stays on the nearest of
 * the cluster's, so one is always ringed and lit until the lens closes. Positions are the stack's
 * skin pixels.
 */
internal class WalLens(
    private val aim: LensAim,
    /** The window's controls, as the skin listed them for the press. */
    private val controls: List<IntRect>,
    /** The small controls of the cluster the lens opened on. */
    private val cluster: List<IntRect>,
) {
    /** Where the crosshair is. */
    val focus: Offset get() = aim.focus

    /** The cluster the lens opened on, where the crosshair may go. */
    val roam: IntRect get() = aim.roam

    /** The control the crosshair is on, or between two the cluster's nearest. */
    val target: IntRect? get() =
        controls.firstOrNull { it.holds(focus) } ?: cluster.minByOrNull { distance(it, focus) }

    /** The skin pixel the press is on: the crosshair's, or the point of [target] nearest it. */
    val point: Pair<Int, Int> get() {
        val x = focus.x.toInt()
        val y = focus.y.toInt()
        val box = target ?: return x to y
        return x.coerceIn(box.left, box.right - 1) to y.coerceIn(box.top, box.bottom - 1)
    }

    /** Moves the crosshair by a finger movement of [delta] skin pixels; false when that pushed the lens away. */
    fun move(delta: Offset): Boolean = aim.move(delta)

    companion object {
        /**
         * The lens a hold at [at] opens, or null: [listed] are the pressed window's, and the one
         * held is the one under [at] or, failing that, the nearest within [slop] (the skin takes a
         * control a finger lands beside). Only a small control ([Loupe.fiddly]) opens it; the
         * crosshair starts on that control, at the point of it nearest the finger.
         */
        fun over(
            listed: List<TouchControl>,
            at: Offset,
            slop: Float,
            reach: Loupe.Reach = Loupe.Reach.UNLIMITED,
        ): WalLens? {
            // a menu's pictures lie over the button that opened them
            val controls = listed.sortedByDescending { it.magnify }
            val held =
                controls.firstOrNull { it.rect.toIntRect().holds(at) }
                    ?: controls
                        .map { it to distance(it.rect.toIntRect(), at) }
                        .filter { it.second <= slop }
                        .minByOrNull { it.second }
                        ?.first
                    ?: return null
            val box = held.rect.toIntRect()
            if (!Loupe.fiddly(box, held.magnify)) return null
            val small =
                controls
                    .filter {
                        it !== held &&
                            Loupe.fiddly(
                                it.rect.toIntRect(),
                                it.magnify,
                            )
                    }.map { it.rect.toIntRect() }
            val roam = Loupe.clusterAround(box, small)
            val cluster = (small + box).filter { roam.contains(it.center) }
            val fixed = fixedAcross(cluster)
            // across a run the crosshair sits in the middle of the held control
            val start =
                Offset(
                    if (fixed == LensAim.Fixed.X) box.center.x.toFloat() else at.x.coerceIn(box.left.toFloat(), box.right - 1f),
                    if (fixed == LensAim.Fixed.Y) box.center.y.toFloat() else at.y.coerceIn(box.top.toFloat(), box.bottom - 1f),
                )
            val centers = cluster.map { Offset(it.center.x.toFloat(), it.center.y.toFloat()) }
            return WalLens(LensAim(start, roam, centers, reach, fixed), controls.map { it.rect.toIntRect() }, cluster)
        }

        /**
         * The lens a hold whose finger is at [finger] but which is aimed at [aimed] opens: on the
         * small control at [aimed] if there is one, else as [over] opens it at [finger]. Near a side
         * of the screen a fingertip cannot get as far out as the controls drawn there
         * ([Loupe.aimedAcross]), so only a small control takes the press from beside it; a large
         * one is pressed where the finger is.
         */
        fun aimed(
            listed: List<TouchControl>,
            finger: Offset,
            aimed: Offset,
            slop: Float,
            reach: Loupe.Reach = Loupe.Reach.UNLIMITED,
        ): WalLens? =
            (if (aimed != finger) over(listed, aimed, slop, reach) else null)
                ?: over(listed, finger, slop, reach)

        /**
         * The axis a run of [cluster] lies across: X for a column, whose controls all share some
         * of their width, Y for a row, whose controls all share some of their height; neither for
         * one control or a block of them.
         */
        internal fun fixedAcross(cluster: List<IntRect>): LensAim.Fixed {
            if (cluster.size < 2) return LensAim.Fixed.NEITHER
            return when {
                cluster.maxOf { it.left } < cluster.minOf { it.right } -> LensAim.Fixed.X
                cluster.maxOf { it.top } < cluster.minOf { it.bottom } -> LensAim.Fixed.Y
                else -> LensAim.Fixed.NEITHER
            }
        }

        private fun IntRect.holds(at: Offset) = at.x >= left && at.x < right && at.y >= top && at.y < bottom

        /** How far [at] lies from [box]. */
        private fun distance(
            box: IntRect,
            at: Offset,
        ): Float {
            val dx = maxOf(box.left - at.x, at.x - (box.right - 1), 0f)
            val dy = maxOf(box.top - at.y, at.y - (box.bottom - 1), 0f)
            return kotlin.math.hypot(dx, dy)
        }
    }
}

/**
 * The lens [WalTouch] shows over the stack where [placed] puts it: it grows in on a spring, and
 * shows [surface]'s latest frame magnified about the crosshair, the control under it ringed.
 */
@Composable
internal fun WalLensLayer(
    surface: WalSurface,
    lens: WalLens?,
    placed: WalPlacement,
) {
    lens ?: return
    var shown by remember(lens) { mutableFloatStateOf(0f) }
    val grown by animateFloatAsState(
        targetValue = shown,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessHigh),
        label = "lens",
    )
    LaunchedEffect(lens) { shown = 1f }
    Canvas(Modifier.fillMaxSize()) {
        // drawn again with each frame, which shows the press the crosshair steers
        surface.frames.intValue
        drawLens(
            origin = Offset(placed.stack.left.toFloat(), placed.stack.top.toFloat()),
            s = placed.skinScale,
            focus = lens.focus,
            roam = lens.roam,
            ring = lens.target,
            grown = grown,
        ) { zoom -> surface.drawMagnified(this, zoom) }
    }
}

/**
 * The lens as a stack on a layer [layer] surface pixels big shows it, the stack where [placed]
 * puts it, at [density] surface pixels per dp; [enabled] reads the Tap assist setting.
 */
internal class WalLensHost(
    private val enabledNow: () -> Boolean,
    private val placed: () -> WalPlacement,
    private val layer: () -> IntSize,
    private val density: Float,
    private val onShow: (WalLens?) -> Unit,
) : WalTouch.Lens {
    override val enabled: Boolean get() = enabledNow()

    override val skinPerPx: Float get() = 1f / placed().skinScale

    override fun open(
        at: TouchPoint,
        controls: List<TouchControl>,
    ): WalLens? {
        val p = placed()
        val s = p.skinScale
        val size = layer()
        val edge = Loupe.Edge.of(density)
        // the finger's room on the screen, in surface pixels, then in skin pixels
        val room =
            Loupe.Reach.on(
                Offset(p.stack.left + at.x, p.stack.top + at.y),
                size.width,
                size.height,
                edge,
            )
        // a hold near a side of the screen is aimed further out than the finger got
        val aimedX = Loupe.aimedAcross(p.stack.left + at.x, size.width, edge) - p.stack.left
        return WalLens.aimed(
            controls,
            finger = Offset(at.x / s, at.y / s),
            aimed = Offset(aimedX / s, at.y / s),
            slop = WalFitting.TOUCH_SLOP_DP * density / s,
            reach = Loupe.Reach(room.left / s, room.up / s, room.right / s, room.down / s),
        )
    }

    override fun show(lens: WalLens?) = onShow(lens)
}
