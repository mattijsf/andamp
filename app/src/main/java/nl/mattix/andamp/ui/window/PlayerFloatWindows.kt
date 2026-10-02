// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.SkinCut
import nl.mattix.andamp.ui.widget.Widget

/**
 * The player and the equalizer as floating windows. They have a fixed size and no resize
 * grip; they float, dock and shade like the other windows (see `WindowParityTest`).
 */
@Composable
@Suppress("LongParameterList") // a window: its player, its art, its size and its place
fun MainFloatWindow(
    vm: nl.mattix.andamp.state.WinampViewModel,
    skin: Skin,
    scale: Int,
    widgets: List<Widget>,
    /** The current height: the full window, or the title bar when shaded. */
    height: Int,
    defaultOffset: IntOffset,
    modifier: Modifier = Modifier,
    /** Winamp's right click on the window: the main menu, from a long press. */
    onLongPress: (() -> Unit)? = null,
) {
    val s: WinampState = vm.state
    FloatingSkinWindow(
        id = WindowStore.MAIN,
        state = s,
        scale = scale,
        width = MAIN_W,
        height = height,
        offset = s.mainOffset,
        defaultOffset = defaultOffset,
        onMove = { s.mainOffset = it },
        titleH = TITLE_BAR_H,
        widgets = widgets,
        // as in Winamp, the player takes its docked windows along
        dragsGroup = true,
        onTitleDoubleTap = { s.setShaded(WindowStore.MAIN, !s.mainShaded, MAIN_H) },
        onLongPress = onLongPress,
        cut = SkinCut(skin, if (s.mainShaded) RegionTxt.Window.MAIN_SHADE else RegionTxt.Window.MAIN),
        modifier = modifier,
        overlay = {
            // the visualizer animates on its own canvas, so a frame repaints only that
            // box; shaded, it is drawn in the strip the title bar keeps for it
            val box = if (s.mainShaded) VisBox.SHADE else VisBox.FULL
            val at = if (s.mainShaded) MAIN_SHADE_VIS else IntOffset(Dest.VISUALIZER.x, Dest.VISUALIZER.y)
            VisualizerOverlay(
                skin,
                s,
                scale,
                box,
                Modifier.offset { IntOffset(at.x * scale, at.y * scale) },
            )
        },
    ) { if (s.mainShaded) drawMainShade(skin, s) else drawMainWindow(skin, s) }
}

@Composable
@Suppress("LongParameterList") // a window: its player, its art, its size and its place
fun EqFloatWindow(
    vm: nl.mattix.andamp.state.WinampViewModel,
    skin: Skin,
    scale: Int,
    widgets: List<Widget>,
    height: Int,
    defaultOffset: IntOffset,
    modifier: Modifier = Modifier,
    /** Winamp's right click on the window: the main menu, from a long press. */
    onLongPress: (() -> Unit)? = null,
) {
    val s: WinampState = vm.state
    FloatingSkinWindow(
        id = WindowStore.EQ,
        state = s,
        scale = scale,
        width = EQ_W,
        height = height,
        offset = s.eqOffset,
        defaultOffset = defaultOffset,
        onMove = { s.eqOffset = it },
        titleH = TITLE_BAR_H,
        widgets = widgets,
        onTitleDoubleTap = { s.setShaded(WindowStore.EQ, !s.eqShaded, EQ_H) },
        onLongPress = onLongPress,
        cut = SkinCut(skin, if (s.eqShaded) RegionTxt.Window.EQ_SHADE else RegionTxt.Window.EQ),
        modifier = modifier,
    ) { if (s.eqShaded) drawEqShade(skin, s) else drawEqWindow(skin, s) }
}

@Composable
private fun VisualizerOverlay(
    skin: nl.mattix.andamp.skin.Skin,
    s: nl.mattix.andamp.state.WinampState,
    scale: Int,
    box: VisBox,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val w = with(density) { (box.w * scale).toDp() }
    val h = with(density) { (box.h * scale).toDp() }
    Spacer(
        modifier
            .size(w, h)
            .drawBehind {
                withTransform({ scale(scale.toFloat(), scale.toFloat(), pivot = Offset.Zero) }) {
                    drawVisualizerBars(skin, s, box)
                }
            },
    )
}
