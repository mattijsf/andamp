// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect

/**
 * The scopes for the shared-element transition that carries a tapped skin from the list into the
 * viewer and back. Both are null where there is no transition to join (a test rendering one tile, a
 * preview drawn on its own), and then [skinHero] does nothing.
 */
val LocalSkinTransition = compositionLocalOf<SharedTransitionScope?> { null }

val LocalSkinAppearance = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The md5 of the skin the viewer is showing, if any. The list stays on screen behind the viewer, so
 * the tile for the open skin hides while its skin is open.
 */
val LocalOpenedSkin = compositionLocalOf<String?> { null }

/**
 * Where the open skin's tile is, in window coordinates.
 *
 * The shared element moves the skin when the state changes. A back gesture changes no state until
 * it is released, so to move the skin toward its tile while the finger pulls, the viewer reads the
 * tile's place from here.
 *
 * Null where there is no grid to go back to, such as a viewer on its own in a test; the gesture
 * then only fades.
 */
val LocalSkinHome = compositionLocalOf<MutableState<Rect?>?> { null }

/**
 * Marks this as the skin [md5], shared between the list and the viewer.
 *
 * The viewer comes and goes with an animation, so it hands over that animation's scope. The tile
 * stays on screen behind the viewer, so it manages its own visibility: hidden while
 * [LocalOpenedSkin] is its skin.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.skinHero(md5: String): Modifier =
    composed {
        val transition = LocalSkinTransition.current ?: return@composed this
        val appearance = LocalSkinAppearance.current
        with(transition) {
            val shared = rememberSharedContentState(key = "skin-$md5")
            if (appearance != null) {
                sharedBounds(shared, animatedVisibilityScope = appearance)
            } else {
                sharedElementWithCallerManagedVisibility(shared, visible = LocalOpenedSkin.current != md5)
            }
        }
    }
