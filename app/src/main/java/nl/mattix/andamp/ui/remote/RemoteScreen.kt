// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.InPlayerVisualizerDriver
import nl.mattix.andamp.ui.PlayerViewport
import nl.mattix.andamp.ui.PlayerViewportBox
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.SkinCut
import nl.mattix.andamp.ui.UiClocks
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.widget.hitTest
import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.EQ_W
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.VisBox
import nl.mattix.andamp.ui.window.VisualizerOverlay
import nl.mattix.andamp.ui.window.WindowControls
import nl.mattix.andamp.ui.window.drawEqWindow
import nl.mattix.andamp.ui.window.drawMainWindow
import nl.mattix.andamp.ui.window.eqWindowWidgets
import nl.mattix.andamp.ui.window.mainWindowWidgets
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What a lent surface shows: one window at a time, on black, in the middle of [within].
 * [within] is in the surface's pixels; null is all of it. The window is drawn at the largest
 * whole scale that fits there, or with [fill] as large as fits at all; see [remoteViewport].
 * The D in the main window's clutter bar is lit by [fill] and calls [onFill]: this surface's
 * own Double Size. The A is never lit here, and does nothing.
 *
 * It starts on the main window. The EQ button there swaps the screen to the equalizer and the
 * PL button to the playlist; the close button of either swaps it back. Which one is up belongs
 * to this surface and is not kept.
 *
 * The main window and the equalizer are drawn whole. The controls that need another window or
 * a menu do nothing here; see [mainAlone] and [eqAlone]. The playlist is [RemoteList], a list
 * to pick a song from, which fills the room.
 *
 * [sliders] is kept told where the sliders of the window that is up are, for a drag the
 * surface's owner reports by its distance alone; see [HeldSlider].
 */
@Composable
internal fun RemoteScreen(
    vm: WinampViewModel,
    skin: Skin,
    within: IntRect?,
    scroll: ListScroll,
    sliders: Sliders = Sliders(),
    fill: Boolean = false,
    onFill: () -> Unit = {},
) {
    val s = vm.state
    var up by remember { mutableStateOf(Up.MAIN) }
    val onFillNow by rememberUpdatedState(onFill)
    val main =
        remember(vm) {
            mainAlone(vm, onEq = { up = Up.EQUALIZER }, onPl = { up = Up.PLAYLIST }, onFill = { onFillNow() })
        }
    val eq = remember(vm) { eqAlone(vm, onClose = { up = Up.MAIN }) }
    InPlayerVisualizerDriver(vm)
    UiClocks(s)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val asked = within ?: IntRect(0, 0, constraints.maxWidth, constraints.maxHeight)
        val fit = remoteViewport(asked.width, asked.height, fill)
        // the windows are laid out on the viewport's surface, so the room is in its pixels
        PlayerViewportBox(fit) {
            val room = IntRect(fit.inner(asked.left), fit.inner(asked.top), fit.inner(asked.right), fit.inner(asked.bottom))
            if (up == Up.PLAYLIST) {
                SideEffect { sliders.at = Sliders.NONE }
                RemoteList(vm, skin, room, fit, scroll, onClose = { up = Up.MAIN })
            } else {
                // the two windows are one size, so the swap moves nothing
                val left = room.left + (room.width - MAIN_W * fit.scale) / 2
                val top = room.top + (room.height - MAIN_H * fit.scale) / 2
                val controls = if (up == Up.EQUALIZER) eq else main
                SideEffect { sliders.at = { x, y -> sliderAt(controls, IntOffset(left, top), fit, x, y) } }
                Box(Modifier.offset { IntOffset(left, top) }) {
                    if (up == Up.EQUALIZER) EqualizerAlone(skin, s, fit.scale, eq) else MainAlone(skin, s, fit.scale, main, fill)
                }
            }
        }
    }
}

@Composable
private fun MainAlone(
    skin: Skin,
    s: WinampState,
    scale: Int,
    widgets: List<Widget>,
    fill: Boolean,
) {
    ScaledWindowCanvas(MAIN_W, MAIN_H, scale, s, widgets, cut = SkinCut(skin, RegionTxt.Window.MAIN)) {
        drawMainWindow(skin, s, alwaysOnTop = false, doubleSize = fill)
    }
    // the visualizer animates on its own canvas, so a frame repaints only that box
    VisualizerOverlay(
        skin,
        s,
        scale,
        VisBox.FULL,
        Modifier.offset { IntOffset(Dest.VISUALIZER.x * scale, Dest.VISUALIZER.y * scale) },
    )
}

@Composable
private fun EqualizerAlone(
    skin: Skin,
    s: WinampState,
    scale: Int,
    widgets: List<Widget>,
) {
    ScaledWindowCanvas(EQ_W, EQ_H, scale, s, widgets, cut = SkinCut(skin, RegionTxt.Window.EQ)) {
        drawEqWindow(skin, s)
    }
}

/** The window a lent surface is showing. */
private enum class Up { MAIN, EQUALIZER, PLAYLIST }

/**
 * The slider among [widgets] that a touch at ([x], [y]) on the surface lands on, or null.
 * The window's top left corner is at [origin] on [fit]'s surface, which is drawn
 * [PlayerViewport.shrink] times its size.
 */
internal fun sliderAt(
    widgets: List<Widget>,
    origin: IntOffset,
    fit: PlayerViewport,
    x: Float,
    y: Float,
): SurfaceSlider? {
    fun inWindow(
        onSurface: Float,
        from: Int,
    ) = floor((onSurface / fit.shrink - from) / fit.scale).toInt()

    fun onSurface(
        inWindow: Float,
        from: Int,
    ) = (from + inWindow * fit.scale) * fit.shrink

    val hit = hitTest(widgets, IntOffset(inWindow(x, origin.x), inWindow(y, origin.y))) ?: return null
    val handle = hit.handle ?: return null
    val at = hit.bounds
    return SurfaceSlider(
        hit.id,
        IntRect(
            onSurface(at.left.toFloat(), origin.x).roundToInt(),
            onSurface(at.top.toFloat(), origin.y).roundToInt(),
            onSurface(at.right.toFloat(), origin.x).roundToInt(),
            onSurface(at.bottom.toFloat(), origin.y).roundToInt(),
        ),
    ) { handle().let { Offset(onSurface(it.x, origin.x), onSurface(it.y, origin.y)) } }
}

/** How many pixels a virtual pixel gets in a room of this size: whole, and at least one. */
internal fun wholeScale(
    width: Int,
    height: Int,
): Int = minOf(width / MAIN_W, height / MAIN_H).coerceAtLeast(1)

/**
 * How a window is fitted into a room of [width] by [height] surface pixels.
 *
 * Without [fill], it is drawn at [wholeScale] and not shrunk, so every pixel of the skin is a
 * whole number of the surface's. With [fill], it is as large as the room takes in its own
 * shape: laid out at the next whole scale up and drawn smaller as one picture, as a player
 * that fills a phone's screen is (see [nl.mattix.andamp.ui.playerViewport]). A room that is a
 * whole multiple of the window is filled without shrinking.
 */
internal fun remoteViewport(
    width: Int,
    height: Int,
    fill: Boolean,
): PlayerViewport {
    if (!fill || width <= 0 || height <= 0) return PlayerViewport(wholeScale(width, height), 1f)
    // compared as whole numbers, so a room of exactly the window's shape is decided one way
    val widthDecides = width.toLong() * MAIN_H <= height.toLong() * MAIN_W
    val room = if (widthDecides) width else height
    val window = if (widthDecides) MAIN_W else MAIN_H
    val scale = ((room + window - 1) / window).coerceAtLeast(1)
    return PlayerViewport(scale, room.toFloat() / (window * scale))
}

/**
 * The main window's controls on a surface that shows one window: those that need nothing but
 * the window itself ([MAIN_ALONE]), the EQ and PL buttons, which call [onEq] and [onPl] in
 * place of opening a window beside it, and the clutter bar's D, which calls [onFill] in place
 * of the phone's Double Size.
 */
internal fun mainAlone(
    vm: WindowControls,
    onEq: () -> Unit,
    onPl: () -> Unit,
    onFill: () -> Unit,
): List<Widget> =
    mainWindowWidgets(vm, onDoubleSize = onFill, onOptions = {}).mapNotNull { stock ->
        val at = stock.bounds
        when (stock.id) {
            EQ_SWITCH -> button(EQ_SWITCH, at.left, at.top, at.width, at.height, onTap = onEq)
            PL_SWITCH -> button(PL_SWITCH, at.left, at.top, at.width, at.height, onTap = onPl)
            in MAIN_ALONE, FILL_SWITCH -> stock
            else -> null
        }
    }

/**
 * The equalizer's controls on a surface that shows one window: everything but the preset
 * menu and the shade button. The close button calls [onClose], and takes a touch anywhere in
 * [EQ_CLOSE_REACH]: on a car's screen the 9x9 button itself is a small thing to hit.
 */
internal fun eqAlone(
    vm: WindowControls,
    onClose: () -> Unit,
): List<Widget> =
    listOf(
        button(EQ_CLOSE, EQ_CLOSE_REACH.left, EQ_CLOSE_REACH.top, EQ_CLOSE_REACH.width, EQ_CLOSE_REACH.height, onTap = onClose),
    ) + eqWindowWidgets(vm).filter { it.id !in EQ_NOT_ALONE }

private const val EQ_SWITCH = "main.eqToggle"
private const val PL_SWITCH = "main.plToggle"
private const val FILL_SWITCH = "main.clutter.d"
private const val EQ_CLOSE = "eq.close"

/** The right end of the equalizer's title bar, down to the row of buttons under it. */
internal val EQ_CLOSE_REACH = IntRect(235, 0, 275, 17)

/** The controls of the main window that need nothing but the window itself. */
internal val MAIN_ALONE =
    setOf(
        "main.time",
        "main.vis",
        "main.marquee",
        "main.posbar",
        "main.volume",
        "main.balance",
        "main.prev",
        "main.play",
        "main.pause",
        "main.stop",
        "main.next",
        "main.shuffle",
        "main.repeat",
    )

/** The equalizer's controls that are replaced or need more than the window: see [eqAlone]. */
private val EQ_NOT_ALONE = setOf(EQ_CLOSE, "eq.shade", "eq.presets")
