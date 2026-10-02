// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.state.EqOps
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Value to sprite-frame and thumb-travel mappings for the skinned sliders. The formulas are
 * webamp's (MainVolume.tsx, MainBalance.tsx, Band.tsx): volume rounds over 28 frames and
 * balance floors over 27.
 */
object SliderMath {
    /**
     * Where along a horizontal slider a touch fell, 0..1.
     *
     * The value is read from the thumb's center, over [travel] pixels of travel. The home
     * screen widget's sliders use the same function, so a tap there lands where a drag does
     * here.
     */
    fun horizontalFraction(
        x: Float,
        left: Int,
        thumbWidth: Int,
        travel: Int,
    ): Float = ((x - left - thumbWidth / 2f) / travel.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** VOLUME.BMP background frame 0..27 for volume 0..100. */
    fun volumeFrame(volume: Int): Int = if (volume <= 0) 0 else ((volume / 100f * 28).roundToInt() - 1).coerceIn(0, 27)

    /** Volume thumb x-offset 0..51 (65px effective track minus 14px thumb). */
    fun volumeThumbOffset(volume: Int): Int = (volume.coerceIn(0, 100) / 100f * 51).roundToInt()

    /** Balance background frame 0..27 for balance -100..100 (mirrored around center). */
    fun balanceFrame(balance: Int): Int = floor(abs(balance.coerceIn(-100, 100)) / 100f * 27).toInt().coerceIn(0, 27)

    /** Balance thumb x-offset 0..24 for balance -100..100. */
    fun balanceThumbOffset(balance: Int): Int = ((balance.coerceIn(-100, 100) + 100) / 200f * 24).roundToInt()

    /**
     * Sticky center: a balance within [BALANCE_DETENT] of center becomes 0, a detent wide
     * enough for a finger to find.
     */
    fun stickyBalance(raw: Int): Int = if (abs(raw) <= BALANCE_DETENT) 0 else raw

    const val BALANCE_DETENT = 18

    /**
     * Sticky 0dB for the preamp: a value within [PREAMP_DETENT] steps of [centre] becomes
     * [centre]. The detent is narrow, so a small boost or cut beside 0dB can still be set.
     */
    fun stickyPreamp(
        raw: Int,
        centre: Int = EqOps.CENTER,
    ): Int = if (abs(raw - centre) <= PREAMP_DETENT) centre else raw

    const val PREAMP_DETENT = 2

    /** EQMAIN.BMP groove frame 0..27 for a band/preamp value 0..63. */
    fun eqFrame(value: Int): Int = (value.coerceIn(0, 63) / 63f * 27).roundToInt()

    /** EQ thumb y-offset 0..51 (value 63 = top). */
    fun eqThumbOffset(value: Int): Int = ((1f - value.coerceIn(0, 63) / 63f) * 51).roundToInt()

    /** EQ graph row 0..18 for a value 0..63 (row 0 = +12dB). */
    fun eqGraphY(value: Int): Int = ((1f - value.coerceIn(0, 63) / 63f) * 18).roundToInt()

    /** Posbar thumb x-offset 0..219 (248px track minus 29px thumb). */
    fun posbarThumbOffset(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 219).roundToInt()
}

/** `m:ss`, or `h:mm:ss` from one hour. */
fun formatTime(sec: Int): String =
    if (sec >= 3600) {
        "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
    } else {
        "%d:%02d".format(sec / 60, sec % 60)
    }
