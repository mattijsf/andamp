// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.core.model.displayName
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.state.WinampState

/**
 * The parts of the window that change while the rest of it does not.
 *
 * RemoteViews cannot repaint part of a bitmap. So these are drawn as their own small pictures and
 * laid over the places they belong to; each carries its own background, so it replaces that patch
 * of the window's picture. The rest of the window is sent only when it changes.
 */
enum class WidgetLayer(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    /** The minus sign and the four digits. */
    CLOCK(Dest.TIME_MINUS_EX.x, Dest.TIME_DIGIT_Y, CLOCK_W, CLOCK_H),

    /** The title, which scrolls a character a second. */
    MARQUEE(Dest.MARQUEE.x, Dest.MARQUEE.y, Dest.MARQUEE_W, BitmapFont.CHAR_H),

    /** The whole bar, since the thumb moves along it. */
    POSBAR(Dest.POSBAR.x, Dest.POSBAR.y, POSBAR_W, POSBAR_H),

    /** The bitrate and sample-rate digits. A VBR stream changes its bitrate mid-song. */
    READOUT(Dest.KBPS.x, Dest.KBPS.y, READOUT_W, READOUT_H),
    ;

    /**
     * What this layer shows, as a string two states can be compared by, so an unchanged layer is
     * neither drawn nor sent.
     */
    fun signature(state: WinampState): String =
        when (this) {
            // timeRemaining changes every digit without moving the clock
            CLOCK -> "${state.currentTimeSec}/${state.transport}/${state.timeRemaining}"

            MARQUEE -> "${state.currentTrack?.displayName}/${state.marqueeStep}"

            POSBAR -> "${(state.positionFraction * POSBAR_W).toInt()}/${state.pressedWidget}"

            READOUT -> "${state.bitrateKbps}/${state.sampleRateKhz}/${state.transport}"
        }
}

private const val CLOCK_W = 61
private const val CLOCK_H = 13
private const val POSBAR_W = 248
private const val POSBAR_H = 10

/**
 * From KBPS's left edge past KHZ's digits: both readouts, one patch. Wide enough for three digits
 * of kHz: "192" runs from Dest.KHZ.x = 156 to 171. Stops short of Dest.MONO at 212.
 */
private const val READOUT_W = 62
private const val READOUT_H = 6
