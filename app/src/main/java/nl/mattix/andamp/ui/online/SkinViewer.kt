// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.ui.BackPull

/**
 * One skin, large: its name, whether it is installed, and the button that changes that. The
 * screenshot is 275x348 and is shown whole, not cropped to fill the screen.
 */
@Composable
@Suppress("LongParameterList") // one skin, and everything that can be done with it
internal fun SkinViewer(
    /** The back gesture the browser holds; the viewer follows its progress. */
    pull: BackPull,
    /** Whether the skin has arrived and stopped moving; false during the transition. */
    settled: Boolean,
    skin: OnlineSkin,
    live: Skin?,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // not a dialog: a dialog is its own window, and a shared element cannot cross windows

    // everything but the skin fades with the back gesture: the wash, the name, the buttons and the
    // note
    val leaving = Modifier.graphicsLayer { alpha = 1f - pull.progress }
    run {
        Box(
            Modifier
                .fillMaxSize()
                .testTag("$TAG.viewer"),
        ) {
            // the wash takes the tap that closes, on its own node: a clickable around everything
            // would merge the semantics of what is inside it
            Box(
                Modifier
                    .matchParentSize()
                    .then(leaving)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClose,
                    ),
            )
            IconButton(
                onClick = onClose,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(GAP.dp)
                        .then(leaving)
                        .testTag("$TAG.close"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Back to the grid", tint = Color.White)
            }
            // the skin's place is set by the space above it, and the note gets what is left
            // underneath, so a note arriving does not move the skin
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(ABOVE))
                Column(
                    // no padding around the skin itself: it arrives from a list where it filled the
                    // width
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GAP.dp),
                ) {
                    SkinStage(
                        skin,
                        live,
                        flying = !settled,
                        carried = pull.progress > 0f,
                        modifier = Modifier.flyingHome(pull),
                    )
                    Text(
                        skin.filename.asSkinName(),
                        modifier = Modifier.padding(horizontal = GAP.dp).then(leaving),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        skin.md5,
                        modifier = leaving,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = FADED),
                    )
                    Row(
                        leaving,
                        horizontalArrangement = Arrangement.spacedBy(GAP.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (installed) {
                            FilledTonalButton(
                                onClick = onUninstall,
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                modifier = Modifier.testTag("$TAG.uninstall.${skin.md5}"),
                            ) { Text("Uninstall") }
                        } else {
                            Button(
                                onClick = onInstall,
                                enabled = !busy,
                                modifier = Modifier.testTag("$TAG.install.${skin.md5}"),
                            ) { Text(if (busy) "Installing\u2026" else "Install") }
                        }
                        skin.museumUrl?.let { url ->
                            TextButton(
                                onClick = {
                                    // the museum's page has what this screen does not: who made the
                                    // skin and when
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                    }.onFailure { if (it !is ActivityNotFoundException) throw it }
                                },
                                modifier = Modifier.testTag("$TAG.museum"),
                            ) { Text("In the museum") }
                        }
                    }
                }
                // the note gets the remaining space and scrolls inside it
                Box(
                    Modifier.weight(BELOW).padding(top = GAP.dp).then(leaving),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    live?.readme?.let { Readme(it) }
                }
            }
        }
    }
}

/**
 * The note the artist packed with the skin. It fills the space left under the skin and scrolls
 * inside it, so its length does not move the skin.
 */
@Composable
private fun Readme(text: String) {
    Surface(
        color = Color.Black.copy(alpha = READ_WASH),
        shape = RoundedCornerShape(GAP.dp),
        modifier = Modifier.padding(horizontal = GAP.dp).testTag("$TAG.readme"),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color.White.copy(alpha = FADED),
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(GAP.dp),
        )
    }
}

/**
 * The skin at the size it is drawn: the museum's picture holds the space until the skin itself
 * arrives, and then the live player takes over. Both use the same whole pixels; see
 * [wholePlayerWidth].
 *
 * The picture also takes over while the skin is in transition between here and its tile. The player
 * draws in whole virtual pixels and a transition scales continuously; the picture is a bitmap that
 * scales to anything, and it is what the tile shows at the other end.
 */
@Composable
private fun SkinStage(
    skin: OnlineSkin,
    live: Skin?,
    flying: Boolean,
    /** Whether a back gesture is moving the skin; the shared element is then switched off. */
    carried: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val across = with(LocalDensity.current) { wholePlayerWidth(constraints.maxWidth).toDp() }
        Box(
            Modifier
                .width(across)
                .align(Alignment.Center)
                .aspectRatio(SHOT_RATIO)
                // a gesture moves the skin with a graphics-layer transform, so the shared element
                // must not move it as well: it would start from the layout rectangle, which the
                // transform does not change
                .then(if (carried) Modifier else Modifier.skinHero(skin.md5))
                .testTag("$TAG.enlarged"),
        ) {
            AnimatedVisibility(
                visible = live == null || flying,
                // no crossfade into a transition: the player would be drawn scaled through
                // fractions under the picture
                enter = if (flying) EnterTransition.None else fadeIn(tween(FADE_MS)),
                exit = fadeOut(tween(FADE_MS)),
                modifier = Modifier.fillMaxSize(),
            ) {
                SkinShot(
                    url = skin.screenshotUrl,
                    describedAs = skin.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // an if, not a visibility: the player has to be gone the moment a transition starts,
            // without an exit animation
            if (!flying) {
                AnimatedVisibility(
                    visible = live != null,
                    enter = fadeIn(tween(FADE_MS)),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    live?.let { SkinPreviewCanvas(it, Modifier.fillMaxSize().testTag("$TAG.live")) }
                }
            }
        }
    }
}

/** How dark the wash over the museum is. */
private const val SCRIM_ALPHA = 0.62f
private const val FADE_MS = 400
private const val FADED = 0.6f

/** The weights of the space above and below the skin. */
private const val ABOVE = 1f

private const val BELOW = 1.6f

private const val READ_WASH = 0.35f

/**
 * Moves this back toward the tile it came from as the back gesture is pulled. Only the picture
 * travels; see [SkinStage].
 *
 * With no tile to go to (scrolled out of the grid, or a viewer on its own) it shrinks in place.
 */
@Composable
private fun Modifier.flyingHome(pull: BackPull): Modifier {
    val home = LocalSkinHome.current?.value
    var mine by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { mine = it.boundsInWindow() }
        .graphicsLayer {
            val flown = pull.progress
            if (flown <= 0f || mine.isEmpty) return@graphicsLayer
            val to = home ?: Rect(mine.center, mine.width * ADRIFT / 2f)
            val shrink = 1f - flown * (1f - to.width / mine.width)
            scaleX = shrink
            scaleY = shrink
            translationX = (to.center.x - mine.center.x) * flown
            translationY = (to.center.y - mine.center.y) * flown
            transformOrigin = TransformOrigin(HALF, HALF)
        }
}

/** The scale a skin with no tile to return to shrinks to. */
private const val ADRIFT = 0.9f

private const val HALF = 0.5f
