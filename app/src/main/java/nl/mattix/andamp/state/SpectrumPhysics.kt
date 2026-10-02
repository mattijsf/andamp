// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlin.math.max

/**
 * The classic analyzer motion, shared by every signal source: bars jump to
 * the target instantly and decay under gravity; peak caps hold ~30 frames
 * then fall with accelerating speed.
 */
object SpectrumPhysics {
    const val BANDS = 19
    const val MAX = 16f

    /** Winamp's fall per frame: `falloff / 16`, with its default falloff of 12. */
    private const val BAR_FALL = 12f / 16f
    private const val PEAK_HOLD_FRAMES = 30
    private const val PEAK_ACCEL = 0.06f

    /** [targets] holds one 0..16 level per band for this frame. */
    fun step(
        state: WinampState,
        targets: FloatArray,
    ) {
        val levels = state.visLevels
        val peaks = state.visPeaks
        val hold = state.visPeakHold
        val vel = state.visPeakVel
        for (i in 0 until BANDS) {
            val target = targets[i].coerceIn(0f, MAX)
            levels[i] = if (target > levels[i]) target else max(0f, levels[i] - BAR_FALL)
            if (levels[i] >= peaks[i]) {
                peaks[i] = levels[i]
                hold[i] = PEAK_HOLD_FRAMES
                vel[i] = 0f
            } else if (hold[i] > 0) {
                hold[i]--
            } else {
                vel[i] += PEAK_ACCEL
                peaks[i] = max(0f, peaks[i] - vel[i])
            }
        }
    }
}
