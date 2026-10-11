// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.InPlayerVisualizerDriver
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.SkinCut
import nl.mattix.andamp.ui.UiClocks
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
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

/**
 * What a lent surface shows: one window at a time, on black, in the middle of [within] at the
 * largest whole scale that fits there. [within] is in the surface's pixels; null is all of it.
 *
 * It starts on the main window. The EQ button there swaps the screen to the equalizer and the
 * PL button to the playlist; the close button of either swaps it back. Which one is up belongs
 * to this surface and is not kept.
 *
 * The main window and the equalizer are drawn whole. The controls that need another window or
 * a menu do nothing here; see [mainAlone] and [eqAlone]. The playlist is [RemoteList], a list
 * to pick a song from, which fills the room.
 */
@Composable
internal fun RemoteScreen(
    vm: WinampViewModel,
    skin: Skin,
    within: IntRect?,
    scroll: ListScroll,
) {
    val s = vm.state
    var up by remember { mutableStateOf(Up.MAIN) }
    val main = remember(vm) { mainAlone(vm, onEq = { up = Up.EQUALIZER }, onPl = { up = Up.PLAYLIST }) }
    val eq = remember(vm) { eqAlone(vm, onClose = { up = Up.MAIN }) }
    InPlayerVisualizerDriver(vm)
    UiClocks(s)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val room = within ?: IntRect(0, 0, constraints.maxWidth, constraints.maxHeight)
        // the two windows are one size, so the swap moves nothing
        val scale = wholeScale(room.width, room.height)
        val left = room.left + (room.width - MAIN_W * scale) / 2
        val top = room.top + (room.height - MAIN_H * scale) / 2
        if (up == Up.PLAYLIST) {
            RemoteList(vm, skin, room, scale, scroll, onClose = { up = Up.MAIN })
            return@BoxWithConstraints
        }
        Box(Modifier.offset { IntOffset(left, top) }) {
            if (up == Up.EQUALIZER) {
                ScaledWindowCanvas(EQ_W, EQ_H, scale, s, eq, cut = SkinCut(skin, RegionTxt.Window.EQ)) {
                    drawEqWindow(skin, s)
                }
            } else {
                ScaledWindowCanvas(MAIN_W, MAIN_H, scale, s, main, cut = SkinCut(skin, RegionTxt.Window.MAIN)) {
                    drawMainWindow(skin, s)
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
        }
    }
}

/** The window a lent surface is showing. */
private enum class Up { MAIN, EQUALIZER, PLAYLIST }

/** How many pixels a virtual pixel gets in a room of this size: whole, and at least one. */
internal fun wholeScale(
    width: Int,
    height: Int,
): Int = minOf(width / MAIN_W, height / MAIN_H).coerceAtLeast(1)

/**
 * The main window's controls on a surface that shows one window: those that need nothing but
 * the window itself ([MAIN_ALONE]), and the EQ and PL buttons, which call [onEq] and [onPl] in
 * place of opening a window beside it.
 */
internal fun mainAlone(
    vm: WindowControls,
    onEq: () -> Unit,
    onPl: () -> Unit,
): List<Widget> =
    mainWindowWidgets(vm, onOptions = {}).mapNotNull { stock ->
        val at = stock.bounds
        when (stock.id) {
            EQ_SWITCH -> button(EQ_SWITCH, at.left, at.top, at.width, at.height, onTap = onEq)
            PL_SWITCH -> button(PL_SWITCH, at.left, at.top, at.width, at.height, onTap = onPl)
            in MAIN_ALONE -> stock
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
