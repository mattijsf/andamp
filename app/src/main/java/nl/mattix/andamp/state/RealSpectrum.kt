// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * Drives the analyzer from real audio: pulls the latest PCM window from the backend's
 * [AudioTap] each frame, FFTs it and maps the magnitudes onto the classic 19 bands. Motion
 * comes from [SpectrumPhysics], as for the fake signal.
 */
class RealSpectrum(
    private val tap: AudioTap,
    frameMs: Long,
) {
    private val reader = SmoothTapReader(tap, frameMs)
    private val fft = Fft(WINDOW)
    private val window = FloatArray(WINDOW)
    private val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2.0 * PI * it / (WINDOW - 1))).toFloat() }
    private val magnitudes = FloatArray(WINDOW / 2)
    private val targets = FloatArray(SpectrumPhysics.BANDS)
    private val points = FloatArray(POINTS)

    /**
     * Winamp's per-bin tilt: `log10(1 + bias + (i + 1) * (9 - bias) / bins)`, with the bias
     * divided by 1.0025 each bin. It lifts the top of the spectrum, where music has less
     * energy.
     */
    private val equalize =
        FloatArray(BINS).also { table ->
            var bias = INITIAL_BIAS
            for (i in 0 until BINS) {
                table[i] = log10(1.0 + bias + (i + 1) * (TILT_TOP - bias) / BINS).toFloat()
                bias /= BIAS_DECAY
            }
        }

    fun step(state: WinampState) {
        if (!reader.read(window)) {
            targets.fill(0f)
            SpectrumPhysics.step(state, targets)
            return
        }
        // a Hann window before the transform
        for (i in window.indices) window[i] *= hann[i]
        fft.magnitudes(window, magnitudes)
        resample()
        for (band in 0 until SpectrumPhysics.BANDS) {
            var sum = 0f
            for (k in 0 until COLUMNS_PER_BAR) sum += points[band * COLUMNS_PER_BAR + k]
            targets[band] = (sum / COLUMNS_PER_BAR).coerceIn(0f, SpectrumPhysics.MAX)
        }
        SpectrumPhysics.step(state, targets)
    }

    /**
     * Resamples the 512 bins for the bars: Winamp's blend of linear and logarithmic
     * frequency spacing, with the raw magnitude times a per-bin tilt used as a pixel height
     * (no decibel scale). Transcribed from webamp's FFTNullsoft/VisPainter, which took it
     * from WACUP's vis_classic.
     */
    private fun resample() {
        for (x in 0 until POINTS) {
            val linear = x.toFloat() / (POINTS - 1) * (BINS - 1)
            val log = Math.pow(BINS.toDouble(), x.toDouble() / (POINTS - 1)).toFloat()
            val at = ((1f - LOG_BLEND) * linear + LOG_BLEND * log).coerceIn(0f, (BINS - 1).toFloat())
            val lo = at.toInt()
            val hi = (lo + 1).coerceAtMost(BINS - 1)
            val frac = at - lo
            val magnitude = magnitudes[lo] * (1f - frac) + magnitudes[hi] * frac
            points[x] = magnitude * equalize[lo] * GAIN
        }
    }

    private companion object {
        const val WINDOW = 1024
        const val BINS = WINDOW / 2

        /** Columns averaged into one bar. */
        const val COLUMNS_PER_BAR = 4
        const val POINTS = SpectrumPhysics.BANDS * COLUMNS_PER_BAR

        /** 0 is linear frequency spacing, 1 is fully logarithmic. */
        const val LOG_BLEND = 0.91f

        /**
         * Turns a normalized magnitude into Winamp's pixel height. [Fft] is normalized so a
         * full-scale sine reads 1.0 in its bin; Winamp's is not, and it feeds the transform
         * bytes scaled by 1/24, so the same tone lands at (128/24) x (window/4) there.
         */
        const val GAIN = (128f / 24f) * (WINDOW / 4f)

        const val INITIAL_BIAS = 0.04
        const val BIAS_DECAY = 1.0025
        const val TILT_TOP = 9.0
    }
}
