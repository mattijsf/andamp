// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import kotlin.math.roundToInt

/**
 * Classic Winamp oscilloscope columns, algorithm transcribed from webamp's
 * VisPainter.ts (WavePaintHandler): 75 columns sampled from a 576-sample
 * window at a stride of 7 (first sample of each slice), each mapped through
 * Winamp's byte math onto rows 0..15.
 */
class RealOscilloscope(
    tap: AudioTap,
    frameMs: Long,
) {
    private val reader = SmoothTapReader(tap, frameMs)
    private val window = FloatArray(WINDOW)

    fun step(state: WinampState) {
        if (!reader.read(window)) {
            state.visWave.fill(CENTER_ROW)
            return
        }
        for (j in 0 until WinampState.WAVE_COLUMNS) {
            state.visWave[j] = waveRow(window[j * STRIDE])
        }
    }

    companion object {
        const val WINDOW = 576
        const val STRIDE = WINDOW / 75 // slice1st: first sample per 7-sample slice

        /** Silence (byte 128) lands here: round(128/8) - 9 = 7. */
        const val CENTER_ROW = 7

        /** Winamp's mapping: sample as unsigned byte, y = round(byte/8) - 9, clamped to rows 0..15. */
        fun waveRow(sample: Float): Int {
            val byte = (sample.coerceIn(-1f, 1f) + 1f) * 255f / 2f
            return ((byte / 8f).roundToInt() - 9).coerceIn(0, 15)
        }
    }
}
