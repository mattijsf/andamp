// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10

/**
 * The analyzer's columns from real audio: reads the window being heard from the backend's
 * [AudioTap], FFTs it and resamples the magnitudes into [points] columns, each a height on the
 * classic analyzer's scale, where [SpectrumPhysics.MAX] fills a bar. Loud audio goes past it,
 * so a reader clamps.
 *
 * The classic window averages 76 columns into its 19 bars ([RealSpectrum]); a modern skin
 * takes 75 as they are, and the newest [wavePoints] samples of each window as they are for its
 * oscilloscope ([wave]).
 */
class SpectrumColumns(
    tap: AudioTap,
    private val frameMs: Long,
    val points: Int,
    wavePoints: Int = 0,
) {
    private val reader = SmoothTapReader(tap, frameMs)
    private val fft = Fft(WINDOW)
    private val window = FloatArray(WINDOW)
    private val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2.0 * PI * it / (WINDOW - 1))).toFloat() }
    private val magnitudes = FloatArray(BINS)

    /** One height per column, from the last [read] that succeeded. */
    val values = FloatArray(points)

    /** The loudest sample of the last window read, 0..1, or 0 when the read failed. */
    var peak = 0f
        private set

    init {
        require(wavePoints in 0..WINDOW) { "wavePoints $wavePoints outside 0..$WINDOW" }
    }

    /** The newest samples of the last window read that succeeded, -1..1, oldest first: the waveform being heard. */
    val wave = FloatArray(wavePoints)
    private val waveFrom = WINDOW - wavePoints

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

    /** Reads one frame on; see the other overload. */
    fun read(): Boolean = read(frameMs)

    /**
     * Reads the window heard [elapsedMs] after the last read and resamples it into [values].
     * False when the tap has nothing to give; [values] and [wave] then keep the last read.
     */
    fun read(elapsedMs: Long): Boolean {
        if (!reader.read(window, elapsedMs)) {
            peak = 0f
            return false
        }
        var loudest = 0f
        // a Hann window before the transform, the waveform taken before it; a sample that is not a
        // number reads as silence
        for (i in window.indices) {
            if (!window[i].isFinite()) window[i] = 0f
            loudest = maxOf(loudest, abs(window[i]))
            if (i >= waveFrom) wave[i - waveFrom] = window[i].coerceIn(-1f, 1f)
            window[i] *= hann[i]
        }
        peak = loudest.coerceAtMost(1f)
        fft.magnitudes(window, magnitudes)
        resample()
        return true
    }

    /**
     * Resamples the 512 bins into the columns: Winamp's blend of linear and logarithmic
     * frequency spacing, with the raw magnitude times a per-bin tilt used as a pixel height
     * (no decibel scale). Transcribed from webamp's FFTNullsoft/VisPainter, which took it
     * from WACUP's vis_classic.
     */
    private fun resample() {
        for (x in 0 until points) {
            val linear = x.toFloat() / (points - 1) * (BINS - 1)
            val log = Math.pow(BINS.toDouble(), x.toDouble() / (points - 1)).toFloat()
            val at = ((1f - LOG_BLEND) * linear + LOG_BLEND * log).coerceIn(0f, (BINS - 1).toFloat())
            val lo = at.toInt()
            val hi = (lo + 1).coerceAtMost(BINS - 1)
            val frac = at - lo
            val magnitude = magnitudes[lo] * (1f - frac) + magnitudes[hi] * frac
            values[x] = magnitude * equalize[lo] * GAIN
        }
    }

    private companion object {
        const val WINDOW = 1024
        const val BINS = WINDOW / 2

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
