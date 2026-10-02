// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import android.graphics.Typeface
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.SkinCut
import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.PL_W
import nl.mattix.andamp.ui.window.PlaylistLayout
import nl.mattix.andamp.ui.window.PlaylistTextRasterizer
import nl.mattix.andamp.ui.window.drawEqWindow
import nl.mattix.andamp.ui.window.drawMainWindow
import nl.mattix.andamp.ui.window.drawPlaylistWindow
import nl.mattix.andamp.ui.window.drawVisualizerBars
import nl.mattix.andamp.ui.window.eqWindowWidgets
import nl.mattix.andamp.ui.window.mainWindowWidgets
import nl.mattix.andamp.ui.window.playlistWidgets

/**
 * A skin on a running demo player: the app's own windows, widgets and draw functions over a
 * [SkinDemo] state, with no audio and no backend behind it.
 *
 * The stack is the museum's composition, main over equalizer over playlist, each window drawn at
 * one whole-number scale.
 */
@Composable
internal fun SkinPreviewCanvas(
    skin: Skin,
    modifier: Modifier = Modifier,
    /** Whether its controls answer. In a list they do not, so a press opens the row. */
    interactive: Boolean = true,
) {
    val context = LocalContext.current
    val state = remember { SkinDemo.state() }
    val player = remember(state) { SkinDemo.Player(state) }
    val text = remember { PlaylistTextRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH)) }
    val layout = remember { PlaylistLayout(height = PLAYLIST_H, width = PL_W) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(skin) {
        // the infinite-animation clock, not the frame clock: a preview runs for as long as it is
        // open, and this clock tells anything waiting for idleness (a test, an accessibility
        // service) not to wait for it
        val began = withInfiniteAnimationFrameMillis { it }
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                SkinDemo.frame(state, now - began)
                frame = now
            }
        }
    }
    val main = remember(player, interactive) { if (interactive) mainWindowWidgets(player) {} else emptyList() }
    val eq = remember(player, interactive) { if (interactive) eqWindowWidgets(player) else emptyList() }
    val rows = remember(player, layout, interactive) { if (interactive) playlistWidgets(player, layout) else emptyList() }
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        // whole pixels, like the player itself: a fractional scale would filter the art, and a
        // filtered edge bleeds the magenta a skin keeps outside its region
        val scale = wholePlayerWidth(constraints.maxWidth) / MAIN_W
        Column {
            // the same cut the app makes: a skin that ships a REGION.TXT is not a rectangle
            ScaledWindowCanvas(
                MAIN_W,
                MAIN_H,
                scale = scale,
                state = state,
                widgets = main,
                cut = SkinCut(skin, RegionTxt.Window.MAIN),
            ) {
                frame.let { }
                drawMainWindow(skin, state)
                // the analyzer is its own overlay in the app, so the window is not redrawn every
                // frame; here everything is redrawn anyway
                translate(left = Dest.VISUALIZER.x.toFloat(), top = Dest.VISUALIZER.y.toFloat()) {
                    drawVisualizerBars(skin, state)
                }
            }
            ScaledWindowCanvas(
                MAIN_W,
                EQ_H,
                scale = scale,
                state = state,
                widgets = eq,
                cut = SkinCut(skin, RegionTxt.Window.EQ),
            ) {
                frame.let { }
                drawEqWindow(skin, state)
            }
            // cut like the window itself, so the rounded corners show nothing outside the frame's
            // outline
            ScaledWindowCanvas(
                PL_W,
                PLAYLIST_H,
                scale = scale,
                state = state,
                widgets = rows,
                cut = SkinCut(skin),
            ) {
                frame.let { }
                drawPlaylistWindow(skin, state, layout, text, scale)
            }
        }
    }
}

/**
 * The widest a player can be drawn in [availablePx] and still land on whole pixels. Anything that
 * shares a frame with the preview is sized by the same rule.
 */
internal fun wholePlayerWidth(availablePx: Int): Int = (availablePx / MAIN_W).coerceAtLeast(1) * MAIN_W

/** The playlist's height in the museum's screenshots: three windows, 116 each. */
internal const val PLAYLIST_H = 116
