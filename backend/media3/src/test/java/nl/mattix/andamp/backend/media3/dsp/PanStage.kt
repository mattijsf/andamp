// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A constant-power pan, separate from the main window's balance.
 *
 * The two channels follow a quarter-circle, so the loudness stays steady
 * across the sweep, where the balance attenuates one side linearly.
 *
 * `pan` runs 0 (hard left) through 0.5 (center) to 1 (hard right).
 */
internal class PanStage : DspStage {
    @Volatile private var settings: DspSettings.Pan = DspSettings.Pan()

    private var leftGain = 1f
    private var rightGain = 1f
    private var compiled: DspSettings.Pan? = null

    fun update(settings: DspSettings.Pan) {
        this.settings = settings
    }

    override fun process(frame: FloatArray) {
        val current = settings
        if (!current.enabled || frame.size < 2) return
        if (current != compiled) compile(current)
        frame[0] *= leftGain
        frame[1] *= rightGain
        // more than two channels: the pairs beyond the first follow the same law
        var channel = 2
        while (channel + 1 < frame.size) {
            frame[channel] *= leftGain
            frame[channel + 1] *= rightGain
            channel += 2
        }
    }

    override fun reset() = Unit // a gain has no memory to clear

    private fun compile(current: DspSettings.Pan) {
        compiled = current
        val angle = current.pan.coerceIn(0f, 1f) * (PI / 2).toFloat()
        leftGain = cos(angle)
        rightGain = sin(angle)
    }
}
