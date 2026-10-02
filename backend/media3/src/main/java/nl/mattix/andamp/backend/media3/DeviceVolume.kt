// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import kotlin.math.roundToInt

/**
 * Converts between the slider's fraction and the device's own volume steps.
 *
 * Android's media stream has a small number of steps, fifteen on most phones,
 * so the conversion rounds to the nearest step and dragging the slider moves
 * the volume in visible jumps.
 */
internal object DeviceVolume {
    /** The step nearest [fraction] on a device whose volume runs [min]..[max]. */
    fun stepFor(
        fraction: Float,
        min: Int,
        max: Int,
    ): Int {
        if (max <= min) return min
        val within = fraction.coerceIn(0f, 1f)
        return (min + within * (max - min)).roundToInt().coerceIn(min, max)
    }

    /** Where [step] sits on the slider, for a device whose volume runs [min]..[max]. */
    fun fractionFor(
        step: Int,
        min: Int,
        max: Int,
    ): Float {
        if (max <= min) return 0f
        return ((step - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }
}
