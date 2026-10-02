// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.graphics.drawscope.DrawScope
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.widget.hSlider
import kotlin.math.roundToInt

/** Positions in the collapsed equalizer, which shows a volume and a balance slider. */
private object EqShade {
    const val VOLUME_X = 61
    const val VOLUME_W = 97
    const val BALANCE_X = 164
    const val BALANCE_W = 43
    const val SLIDER_Y = 4
    const val THUMB_W = 3
}

fun eqShadeWidgets(vm: WindowControls): List<Widget> {
    val s = vm.state
    return listOf(
        button("eq.shade", 254, 3, 9, 9) { s.setShaded(WindowStore.EQ, false, EQ_H) },
        button("eq.close", 264, 3, 9, 9) { s.eqVisible = false },
        hSlider(
            "eq.volume",
            EqShade.VOLUME_X,
            EqShade.SLIDER_Y,
            EqShade.VOLUME_W,
            7,
            thumbW = EqShade.THUMB_W,
            onChange = { vm.setVolume(it) },
        ),
        hSlider(
            "eq.balance",
            EqShade.BALANCE_X,
            EqShade.SLIDER_Y,
            EqShade.BALANCE_W,
            7,
            thumbW = EqShade.THUMB_W,
            onChange = { vm.setBalance(SliderMath.stickyBalance((it * 200).roundToInt() - 100)) },
        ),
    )
}

fun DrawScope.drawEqShade(
    skin: Skin,
    s: WinampState,
) {
    val sheet = skin[Sheet.EQ_EX]
    sprite(sheet, SpriteMap.EQ_SHADE_BACKGROUND_SELECTED, 0, 0)
    if (s.pressedWidget == "eq.close") sprite(sheet, SpriteMap.EQ_SHADE_CLOSE_BUTTON_ACTIVE, 264, 3)

    val volumeFraction = s.volume / 100f
    val volumeThumb =
        segmentOf(
            volumeFraction,
            SpriteMap.EQ_SHADE_VOLUME_THUMB_LEFT,
            SpriteMap.EQ_SHADE_VOLUME_THUMB_CENTER,
            SpriteMap.EQ_SHADE_VOLUME_THUMB_RIGHT,
        )
    val volumeX =
        EqShade.VOLUME_X + ((EqShade.VOLUME_W - EqShade.THUMB_W) * volumeFraction.coerceIn(0f, 1f)).roundToInt()
    sprite(sheet, volumeThumb, volumeX, EqShade.SLIDER_Y)

    val balanceFraction = (s.balance + 100) / 200f
    val balanceThumb =
        segmentOf(
            balanceFraction,
            SpriteMap.EQ_SHADE_BALANCE_THUMB_LEFT,
            SpriteMap.EQ_SHADE_BALANCE_THUMB_CENTER,
            SpriteMap.EQ_SHADE_BALANCE_THUMB_RIGHT,
        )
    val balanceX =
        EqShade.BALANCE_X + ((EqShade.BALANCE_W - EqShade.THUMB_W) * balanceFraction.coerceIn(0f, 1f)).roundToInt()
    sprite(sheet, balanceThumb, balanceX, EqShade.SLIDER_Y)
}
