// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.widget.hSlider
import nl.mattix.andamp.ui.widget.vSlider
import nl.mattix.andamp.ui.window.SliderMath
import nl.mattix.andamp.ui.window.WindowControls
import nl.mattix.andamp.ui.window.playOrOpen
import kotlin.math.roundToInt

/*
 * Where the controls of the main window and the equalizer are on a surface that shows one window,
 * and what each does there. The windows themselves are drawn by the engine; these are only the
 * places a touch lands, in the window's pixels, from webamp's main-window.css and
 * equalizer-window.css.
 */

/**
 * The main window's controls on a surface that shows one window: those that need nothing but
 * the window itself ([MAIN_ALONE]), the EQ and PL buttons, which call [onEq] and [onPl] in
 * place of opening a window beside it, and the clutter bar's D, which calls [onFill] in place
 * of the phone's Double Size.
 *
 * The balance finds its middle from farther off here ([WIDE_BALANCE_DETENT]): on a car's
 * screen its whole travel is about a finger wide, and a drag is no way to hit one pixel of it.
 */
internal fun mainAlone(
    vm: WindowControls,
    onEq: () -> Unit,
    onPl: () -> Unit,
    onFill: () -> Unit,
): List<Widget> {
    val s = vm.state
    return listOf(
        button(FILL_SWITCH, Dest.CLUTTER_D.left, Dest.CLUTTER_D.top, Dest.CLUTTER_D.width, Dest.CLUTTER_D.height, onTap = onFill),
        button("main.time", 39, 26, 59, 13) { s.timeRemaining = !s.timeRemaining },
        marqueeDrag(s),
        // a tap cycles the visualizer mode, as in Winamp; there is no menu for a long press to open
        button("main.vis", Dest.VISUALIZER.x, Dest.VISUALIZER.y, Dest.VIS_W, Dest.VIS_H, onLongPress = {}) {
            s.visMode = s.visMode.next()
        },
        hSlider(
            "main.posbar",
            Dest.POSBAR.x,
            Dest.POSBAR.y,
            248,
            10,
            thumbW = 29,
            shown = { s.positionFraction },
            onChange = { if (s.transport != Transport.Stopped) s.seekPreview = it },
            onCommit = { if (s.transport != Transport.Stopped) vm.seekTo(it) else s.seekPreview = null },
        ),
        hSlider(
            "main.volume",
            Dest.VOLUME.x,
            Dest.VOLUME.y,
            68,
            13,
            thumbW = 14,
            travelOverride = 51,
            shown = { s.volume / 100f },
            onChange = { vm.setVolume(it) },
        ),
        hSlider(
            "main.balance",
            Dest.BALANCE.x,
            Dest.BALANCE.y,
            38,
            13,
            thumbW = 14,
            shown = { (s.balance + 100) / 200f },
            onChange = { vm.setBalance(SliderMath.stickyBalance((it * 200).roundToInt() - 100, WIDE_BALANCE_DETENT)) },
        ),
        button(EQ_SWITCH, Dest.EQ_TOGGLE.x, Dest.EQ_TOGGLE.y, 23, 12, onTap = onEq),
        button(PL_SWITCH, Dest.PL_TOGGLE.x, Dest.PL_TOGGLE.y, 23, 12, onTap = onPl),
        button("main.prev", Dest.PREV.x, Dest.PREV.y, 23, 18, onTap = vm::previous),
        button("main.play", Dest.PLAY.x, Dest.PLAY.y, 23, 18) { playOrOpen(s.playlist, vm::play) {} },
        button("main.pause", Dest.PAUSE.x, Dest.PAUSE.y, 23, 18, onTap = vm::pause),
        button("main.stop", Dest.STOP.x, Dest.STOP.y, 23, 18, onTap = vm::stop),
        button("main.next", Dest.NEXT.x, Dest.NEXT.y, 22, 18, onTap = vm::next),
        button("main.shuffle", Dest.SHUFFLE.x, Dest.SHUFFLE.y, 47, 15, onTap = vm::toggleShuffle),
        button("main.repeat", Dest.REPEAT.x, Dest.REPEAT.y, 28, 15, onTap = vm::toggleRepeat),
    )
}

/**
 * The title's drag, after webamp's Marquee: a pixel of finger is a pixel of text, the
 * scrolling stops while the finger is down, and the title stays where it was left. The
 * scrolling resumes about a second after the finger leaves.
 */
private fun marqueeDrag(s: WinampState): Widget {
    var from = 0f
    var at = 0
    return Widget(
        "main.marquee",
        IntRect(Dest.MARQUEE.x, Dest.MARQUEE.y, Dest.MARQUEE.x + Dest.MARQUEE_W, Dest.MARQUEE.y + BitmapFont.CHAR_H),
        pointer =
            Widget.Pointer(
                onDown = {
                    from = it.x
                    at = s.marqueePixels
                    s.marqueeHeld = true
                },
                // dragging leftwards moves the text leftwards: the offset grows
                onDrag = { s.marqueePixels = at - (it.x - from).toInt() },
                onUp = { _, _ -> s.marqueeHeld = false },
            ),
    )
}

/** How near its middle the balance has to be brought on a lent surface to rest there, of 100 either way. */
internal const val WIDE_BALANCE_DETENT = 40

/**
 * The equalizer's controls on a surface that shows one window: everything but the preset
 * menu and the shade button. The close button calls [onClose], and takes a touch anywhere in
 * [EQ_CLOSE_REACH]: on a car's screen the 9x9 button itself is a small thing to hit.
 *
 * A double tap on the "+0db" label sets every band to the middle, as in Winamp.
 */
internal fun eqAlone(
    vm: WindowControls,
    onClose: () -> Unit,
): List<Widget> {
    val s = vm.state
    var lastZeroTapAt = 0L
    return listOf(
        button(EQ_CLOSE, EQ_CLOSE_REACH.left, EQ_CLOSE_REACH.top, EQ_CLOSE_REACH.width, EQ_CLOSE_REACH.height, onTap = onClose),
        button("eq.zerodb", 45, 64, 22, 8) {
            val now = System.currentTimeMillis()
            if (now - lastZeroTapAt < DOUBLE_TAP_MS) vm.resetBands()
            lastZeroTapAt = now
        },
        button("eq.on", Dest.EQ_ON.x, Dest.EQ_ON.y, 26, 12, onTap = { vm.toggleEq() }),
        button("eq.auto", Dest.EQ_AUTO.x, Dest.EQ_AUTO.y, 32, 12) { s.eqAuto = !s.eqAuto },
        vSlider(
            "eq.preamp",
            Dest.EQ_PREAMP_X,
            Dest.EQ_SLIDER_Y,
            14,
            63,
            thumbH = 11,
            shown = { s.preamp / 63f },
            onChange = { vm.setPreamp(SliderMath.stickyPreamp((it * 63).roundToInt())) },
        ),
    ) +
        List(EQ_BANDS) { i ->
            vSlider(
                "eq.band$i",
                Dest.eqBandX(i),
                Dest.EQ_SLIDER_Y,
                14,
                63,
                thumbH = 11,
                // one drag draws across every band, as in Winamp
                dragGroup = EQ_BAND_GROUP,
                shown = { s.eqBands[i] / 63f },
                onChange = { vm.setBand(i, (it * 63).roundToInt()) },
            )
        }
}

private const val EQ_SWITCH = "main.eqToggle"
private const val PL_SWITCH = "main.plToggle"
private const val FILL_SWITCH = "main.clutter.d"
private const val EQ_CLOSE = "eq.close"
private const val EQ_BANDS = 10
private const val EQ_BAND_GROUP = "eq.bands"
private const val DOUBLE_TAP_MS = 400L

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
