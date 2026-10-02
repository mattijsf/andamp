// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.abs

/**
 * AVS's own beat detector, transcribed from `main.cpp` in vis_avs (BSD; see
 * NOTICE.md).
 *
 * The whole frame's waveform is summed as full-wave amplitude; a fast peak
 * (`peak1`) drifts toward a slow envelope (`peak2`), and a beat is the sum
 * clearing the fast peak by 34/32 while also clearing an absolute floor. The
 * floor keeps silence from beating, and a sustained level stops beating when
 * the peak catches up.
 */
class AvsBeat {
    private var peak1 = 0L
    private var peak1Peak = 0L
    private var peak2 = 0L
    private var count = 0

    /** Feed one frame's waveform (-1..1); answers whether this frame is a beat. */
    fun update(waveform: FloatArray): Boolean {
        // the original sums |sample| in byte units over the 576-sample window
        var sum = 0L
        for (sample in waveform) {
            sum += abs((sample * HALF_RANGE).toInt()).coerceAtMost(HALF_RANGE_INT).toLong()
        }

        peak1 = (peak1 * DRIFT_KEEP + peak2 * DRIFT_TAKE) / DRIFT_SCALE
        count++

        return if (sum >= (peak1 * THRESHOLD_NUM) / THRESHOLD_DEN && sum > FLOOR) {
            val beat = count > 0
            if (beat) count = 0
            peak1 = (sum + peak1Peak) / 2
            peak1Peak = sum
            beat
        } else {
            if (sum > peak2) {
                peak2 = sum
            } else {
                peak2 = (peak2 * DECAY_NUM) / DECAY_DEN
            }
            false
        }
    }

    /** Feeds one frame of silence, which lets the envelopes decay. */
    fun idle() {
        update(SILENCE)
    }

    private companion object {
        const val HALF_RANGE = 128f
        const val HALF_RANGE_INT = 128

        /** peak1 = (peak1*125 + peak2*3) / 128, per frame. */
        const val DRIFT_KEEP = 125L
        const val DRIFT_TAKE = 3L
        const val DRIFT_SCALE = 128L

        /** A beat clears the fast peak by 34/32... */
        const val THRESHOLD_NUM = 34L
        const val THRESHOLD_DEN = 32L

        /** ...and an absolute floor of 16 per sample over the 576 window. */
        const val FLOOR = 576L * 16L

        /** The slow envelope decays by 14/16 per quiet frame. */
        const val DECAY_NUM = 14L
        const val DECAY_DEN = 16L

        val SILENCE = FloatArray(AvsAudioFrame.SAMPLES)
    }
}
