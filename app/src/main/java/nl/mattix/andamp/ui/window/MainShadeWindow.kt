// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.Sprite
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.bitmapText
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.widget.hSlider
import kotlin.math.roundToInt

/**
 * Where the shaded player keeps its strip of visualizer; webamp's `.shade #visualizer`.
 */
internal val MAIN_SHADE_VIS =
    androidx.compose.ui.unit
        .IntOffset(79, 5)

internal object MainShade {
    /** The transport buttons' hit rectangles; their art is part of the bar. */
    val PREV = Sprite(169, 2, 7, 10)
    val PLAY = Sprite(176, 2, 10, 10)
    val PAUSE = Sprite(186, 2, 9, 10)
    val STOP = Sprite(195, 2, 9, 10)
    val NEXT = Sprite(204, 2, 10, 10)
    val EJECT = Sprite(215, 2, 10, 10)

    const val POSITION_X = 226
    const val POSITION_Y = 4
    const val POSITION_W = 17
    const val THUMB_W = 3

    /** The clock, in the 5x6 TEXT.BMP font. */
    const val TIME_X = 127
    const val TIME_Y = 4
    const val TIME_W = 25 // "00:00" in the 5px font
    const val TIME_H = 6

    /** The strip of visualizer the shaded bar keeps; see [MAIN_SHADE_VIS]. */
    val VIS_X = MAIN_SHADE_VIS.x
    val VIS_Y = MAIN_SHADE_VIS.y
}

private fun hitRect(
    id: String,
    at: Sprite,
    onTap: () -> Unit,
) = button(id, at.x, at.y, at.w, at.h, onTap = onTap)

@Suppress("LongParameterList") // a title bar with a player in it
fun mainShadeWidgets(
    vm: WindowControls,
    /**
     * Opens the file browser: Play calls it on an empty queue, Eject always. True when Play
     * opened it, so that what is picked is also started.
     */
    onOpenFile: (Boolean) -> Unit = {},
    onOptions: () -> Unit,
    onExit: () -> Unit,
    /** The menu a long press on the visualizer strip opens, as in the full window. */
    visualizerMenu: (MenuAnchor) -> AmpMenu? = { null },
): List<Widget> {
    val s = vm.state
    return listOf(
        // the visualizer and the clock act on the same state as in the full window
        Widget(
            "main.vis",
            IntRect(MainShade.VIS_X, MainShade.VIS_Y, MainShade.VIS_X + VisBox.SHADE.w, MainShade.VIS_Y + VisBox.SHADE.h),
            taps =
                Widget.Taps(
                    onTap = { s.visMode = s.visMode.next() },
                    onLongPress = {
                        visualizerMenu(
                            MenuAnchor(WindowStore.MAIN, MainShade.VIS_X, MainShade.VIS_Y, VisBox.SHADE.w, VisBox.SHADE.h),
                        )?.let { s.activeMenu = it }
                    },
                ),
        ),
        button("main.time", MainShade.TIME_X, MainShade.TIME_Y, MainShade.TIME_W, MainShade.TIME_H) {
            s.timeRemaining = !s.timeRemaining
        },
        button("main.options", 6, 3, 9, 9, onTap = onOptions),
        button("main.minimize", 244, 3, 9, 9) {},
        button("main.shade", 254, 3, 9, 9) { s.setShaded(WindowStore.MAIN, false, MAIN_H) },
        button("main.close", 264, 3, 9, 9, onTap = onExit),
        hitRect("main.prev", MainShade.PREV, vm::previous),
        hitRect("main.play", MainShade.PLAY) { playOrOpen(s.playlist, vm::play) { onOpenFile(true) } },
        hitRect("main.pause", MainShade.PAUSE, vm::pause),
        hitRect("main.stop", MainShade.STOP, vm::stop),
        hitRect("main.next", MainShade.NEXT, vm::next),
        hitRect("main.eject", MainShade.EJECT) { onOpenFile(false) },
        hSlider(
            "main.posbar",
            MainShade.POSITION_X,
            MainShade.POSITION_Y,
            MainShade.POSITION_W,
            7,
            thumbW = MainShade.THUMB_W,
            onChange = { if (s.transport != Transport.Stopped) s.seekPreview = it },
            onCommit = { if (s.transport != Transport.Stopped) vm.seekTo(it) else s.seekPreview = null },
        ),
    )
}

fun DrawScope.drawMainShade(
    skin: Skin,
    s: WinampState,
) {
    val titlebar = skin[Sheet.TITLEBAR]
    sprite(titlebar, SpriteMap.MAIN_SHADE_BACKGROUND_SELECTED, 0, 0)
    if (s.pressedWidget == "main.shade") {
        sprite(titlebar, SpriteMap.MAIN_SHADE_BUTTON_SELECTED_DEPRESSED, 254, 3)
    }
    if (s.pressedWidget == "main.close") sprite(titlebar, SpriteMap.MAIN_CLOSE_BUTTON_DEPRESSED, 264, 3)
    if (s.pressedWidget == "main.options") sprite(titlebar, SpriteMap.MAIN_OPTIONS_BUTTON_DEPRESSED, 6, 3)

    // the visualizer's background; the signal is drawn on its own overlay
    drawRect(
        skin.visColors[0],
        androidx.compose.ui.geometry
            .Offset(MainShade.VIS_X.toFloat(), MainShade.VIS_Y.toFloat()),
        androidx.compose.ui.geometry
            .Size(VisBox.SHADE.w.toFloat(), VisBox.SHADE.h.toFloat()),
    )

    // the clock, which blinks while paused
    if (s.transport != Transport.Stopped && (s.transport != Transport.Paused || s.blinkOn)) {
        bitmapText(skin[Sheet.TEXT], shadeTime(s), MainShade.TIME_X, MainShade.TIME_Y)
    }

    // the seek bar is hidden while stopped
    if (s.transport == Transport.Stopped) return
    sprite(titlebar, SpriteMap.MAIN_SHADE_POSITION_BACKGROUND, MainShade.POSITION_X, MainShade.POSITION_Y)
    val travel = MainShade.POSITION_W - MainShade.THUMB_W
    val fraction = s.positionFraction
    val offset = (fraction.coerceIn(0f, 1f) * travel).roundToInt()
    val thumb =
        when (offset) {
            0 -> SpriteMap.MAIN_SHADE_POSITION_THUMB_LEFT
            travel -> SpriteMap.MAIN_SHADE_POSITION_THUMB_RIGHT
            else -> SpriteMap.MAIN_SHADE_POSITION_THUMB
        }
    sprite(titlebar, thumb, MainShade.POSITION_X + offset, MainShade.POSITION_Y)
}
