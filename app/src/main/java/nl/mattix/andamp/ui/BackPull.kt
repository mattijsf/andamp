// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * How far the back gesture has been pulled, and from which side.
 *
 * Held apart from the drawing because a screen with several levels has one handler for the
 * gesture and a different thing leaving on each step; see [pulledAside].
 */
class BackPull internal constructor() {
    private val pulled = Animatable(0f)

    /** 0 at rest, 1 when the gesture has been completed. */
    val progress: Float get() = pulled.value

    /** Whether the finger came from the right edge; the content drifts away from that edge. */
    var fromRight by mutableStateOf(true)
        internal set

    internal suspend fun follow(event: BackEventCompat) {
        fromRight = event.swipeEdge == BackEventCompat.EDGE_RIGHT
        pulled.snapTo(event.progress)
    }

    /**
     * Animates a completed gesture to 1. Snapping to 0 here would show the content at full
     * size for a frame before the transition that follows; [rest] resets it afterwards.
     */
    internal suspend fun land() {
        pulled.animateTo(1f, tween(LANDING_MS))
    }

    /** Animates back to 0, for an abandoned gesture. */
    internal suspend fun springBack() {
        pulled.animateTo(0f, spring())
    }

    /** Snaps to 0, once what left has finished leaving. */
    internal suspend fun rest() {
        pulled.snapTo(0f)
    }
}

/** A pull that nothing drives, for a screen that takes [PredictiveBackHandler] itself. */
@Composable
fun rememberBackPull(): BackPull = remember { BackPull() }

/**
 * Takes the back gesture for this screen and reports its progress, so the content can follow
 * the finger and spring back when the gesture is abandoned.
 *
 * [onBack] decides where back goes, so a screen with several levels needs only one of these.
 */
@Composable
fun rememberBackPull(
    enabled: Boolean = true,
    onBack: () -> Unit,
): BackPull {
    val pull = remember { BackPull() }
    PredictiveBackHandler(enabled) { gesture ->
        try {
            gesture.collect { pull.follow(it) }
            onBack()
            pull.land()
        } catch (abandoned: CancellationException) {
            // abandoned: spring back. The spring runs outside the cancellation that ended
            // the gesture, or it is cancelled before its first frame
            withContext(NonCancellable) { pull.springBack() }
            throw abandoned
        }
    }
    return pull
}

/**
 * Draws this as the thing leaving under [pull]: it shrinks, drifts away from the edge the
 * finger came from and rounds its corners.
 *
 * [drawn] is whether this level of the screen is the one on its way out. Every level carries
 * the modifier and only one passes true.
 */
fun Modifier.pulledAside(
    pull: BackPull,
    drawn: Boolean = true,
): Modifier =
    this.graphicsLayer {
        val pulled = if (drawn) pull.progress else 0f
        val shrink = 1f - AWAY * pulled
        scaleX = shrink
        scaleY = shrink
        translationX = (if (pull.fromRight) -1f else 1f) * DRIFT * size.width * pulled
        transformOrigin = TransformOrigin(PIVOT, PIVOT)
        shape = RoundedCornerShape(ROUND.dp * pulled)
        clip = pulled > 0f
    }

/** How far a screen shrinks while the gesture is held. */
private const val AWAY = 0.12f

/** How far it slides, as a share of its own width. */
private const val DRIFT = 0.06f

/** The corner it rounds to at a full pull, in dp. */
private const val ROUND = 32

private const val PIVOT = 0.5f

/** How long a completed gesture takes to animate to the end. */
private const val LANDING_MS = 180
