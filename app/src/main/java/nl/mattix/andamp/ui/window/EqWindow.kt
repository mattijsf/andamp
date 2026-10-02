// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.widget.vSlider
import kotlin.math.roundToInt

const val EQ_W = 275
const val EQ_H = 116
private const val DOUBLE_TAP_MS = 400L
private const val EQ_BAND_GROUP = "eq.bands"

fun eqWindowWidgets(vm: WindowControls): List<Widget> {
    val s = vm.state
    // double-tap on the "+0db" label resets all bands (hit target from webamp's #zerodb)
    var lastZeroTapAt = 0L
    val widgets =
        mutableListOf(
            button("eq.close", 264, 3, 9, 9) { s.eqVisible = false },
            button("eq.shade", 254, 3, 9, 9, inert = { !s.shadeEnabled }) { s.setShaded(WindowStore.EQ, true, EQ_H) },
            button("eq.zerodb", 45, 64, 22, 8) {
                val now = System.currentTimeMillis()
                if (now - lastZeroTapAt < DOUBLE_TAP_MS) vm.resetBands()
                lastZeroTapAt = now
            },
            button("eq.on", Dest.EQ_ON.x, Dest.EQ_ON.y, 26, 12, onTap = { vm.toggleEq() }),
            button("eq.auto", Dest.EQ_AUTO.x, Dest.EQ_AUTO.y, 32, 12) { s.eqAuto = !s.eqAuto },
            button("eq.presets", Dest.EQ_PRESETS.x, Dest.EQ_PRESETS.y, 44, 12) {
                s.activeMenu = vm.presetsMenu(MenuAnchor(WindowStore.EQ, Dest.EQ_PRESETS.x, Dest.EQ_PRESETS.y, 44, 12))
            },
            vSlider(
                "eq.preamp",
                Dest.EQ_PREAMP_X,
                Dest.EQ_SLIDER_Y,
                14,
                63,
                thumbH = 11,
                onChange = { vm.setPreamp(SliderMath.stickyPreamp((it * 63).roundToInt())) },
            ),
        )
    for (i in 0 until 10) {
        widgets +=
            vSlider(
                "eq.band$i",
                Dest.eqBandX(i),
                Dest.EQ_SLIDER_Y,
                14,
                63,
                thumbH = 11,
                // one drag draws across every band, as in Winamp
                dragGroup = EQ_BAND_GROUP,
                onChange = { vm.setBand(i, (it * 63).roundToInt()) },
            )
    }
    return widgets
}

fun DrawScope.drawEqWindow(
    skin: Skin,
    s: WinampState,
) {
    val sheet = skin[Sheet.EQMAIN]
    val pressed = s.pressedWidget

    sprite(sheet, SpriteMap.EQ_WINDOW_BACKGROUND, 0, 0)
    sprite(sheet, SpriteMap.EQ_TITLE_BAR_SELECTED, 0, 0)
    sprite(sheet, if (pressed == "eq.close") SpriteMap.EQ_CLOSE_BUTTON_ACTIVE else SpriteMap.EQ_CLOSE_BUTTON, 264, 3)

    sprite(
        sheet,
        when {
            s.eqOn && pressed == "eq.on" -> SpriteMap.EQ_ON_BUTTON_SELECTED_DEPRESSED
            s.eqOn -> SpriteMap.EQ_ON_BUTTON_SELECTED
            pressed == "eq.on" -> SpriteMap.EQ_ON_BUTTON_DEPRESSED
            else -> SpriteMap.EQ_ON_BUTTON
        },
        Dest.EQ_ON,
    )
    sprite(
        sheet,
        when {
            s.eqAuto && pressed == "eq.auto" -> SpriteMap.EQ_AUTO_BUTTON_SELECTED_DEPRESSED
            s.eqAuto -> SpriteMap.EQ_AUTO_BUTTON_SELECTED
            pressed == "eq.auto" -> SpriteMap.EQ_AUTO_BUTTON_DEPRESSED
            else -> SpriteMap.EQ_AUTO_BUTTON
        },
        Dest.EQ_AUTO,
    )
    sprite(
        sheet,
        if (pressed == "eq.presets") SpriteMap.EQ_PRESETS_BUTTON_SELECTED else SpriteMap.EQ_PRESETS_BUTTON,
        Dest.EQ_PRESETS,
    )

    drawEqGraph(sheet, s)

    drawEqSlider(sheet, Dest.EQ_PREAMP_X, s.preamp, pressed == "eq.preamp")
    for (i in 0 until 10) {
        drawEqSlider(sheet, Dest.eqBandX(i), s.eqBands[i], pressed == "eq.band$i")
    }
}

private fun DrawScope.drawEqSlider(
    sheet: androidx.compose.ui.graphics.ImageBitmap,
    x: Int,
    value: Int,
    pressed: Boolean,
) {
    sprite(sheet, SpriteMap.eqSliderFrame(SliderMath.eqFrame(value)), x, Dest.EQ_SLIDER_Y)
    val thumb = if (pressed) SpriteMap.EQ_SLIDER_THUMB_SELECTED else SpriteMap.EQ_SLIDER_THUMB
    sprite(sheet, thumb, x + 1, Dest.EQ_SLIDER_Y + SliderMath.eqThumbOffset(value))
}

/**
 * The EQ curve: 10 band points spread over the 113px graph, joined with
 * linear segments, colored per-row by the 1x19 EQ_GRAPH_LINE_COLORS ramp.
 */
private fun DrawScope.drawEqGraph(
    sheet: androidx.compose.ui.graphics.ImageBitmap,
    s: WinampState,
) {
    sprite(sheet, SpriteMap.EQ_GRAPH_BACKGROUND, Dest.EQ_GRAPH)

    val ramp = SpriteMap.EQ_GRAPH_LINE_COLORS
    if (ramp.x >= sheet.width || ramp.y + ramp.h > sheet.height) return
    val pixels = sheet.toPixelMap(startX = ramp.x, startY = ramp.y, width = 1, height = 19)
    val gx = Dest.EQ_GRAPH.x
    val gy = Dest.EQ_GRAPH.y

    fun bandY(v: Int) = SliderMath.eqGraphY(v)

    // preamp line first (a faint 113x1 strip positioned by the preamp value)
    sprite(sheet, SpriteMap.EQ_PREAMP_LINE, gx, gy + bandY(s.preamp))

    var prevX = 0
    var prevY = bandY(s.eqBands[0])
    for (i in 1 until 10) {
        val x = (i * 112f / 9f).roundToInt()
        val y = bandY(s.eqBands[i])
        val dx = x - prevX
        for (step in 0..dx) {
            val px = prevX + step
            val py = (prevY + (y - prevY) * step.toFloat() / dx).roundToInt().coerceIn(0, 18)
            drawRect(pixels[0, py], Offset((gx + px).toFloat(), (gy + py).toFloat()), Size(1f, 1f))
        }
        prevX = x
        prevY = y
    }
}
