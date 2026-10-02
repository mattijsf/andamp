// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the app's mono PCM into what AVS components read.
 *
 * AVS handed its components 576 waveform samples and 576 spectrum values a
 * frame. The app's audio tap delivers whatever the playback engine's hop size
 * is, so this keeps a ring of the most recent samples and cuts the newest 576
 * out of it each frame; the spectrum is a 512-point FFT over the first 512 of
 * those, its magnitudes stretched across AVS's 576 bins.
 *
 * The beat is not computed here: the caller passes it to [frame] as a flag.
 */
class AvsAudio {
    private val ring = FloatArray(RING)

    /** A Long: an Int of samples overflows after 13.5 hours of 44.1kHz audio. */
    private var written = 0L

    private val real = FloatArray(FFT_SIZE)
    private val imag = FloatArray(FFT_SIZE)
    private val hann = FloatArray(FFT_SIZE) { (HALF - HALF * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat() }

    /** Feed whatever the tap delivered this frame, oldest first, -1..1. */
    fun feed(samples: FloatArray) {
        for (sample in samples) {
            ring[(written % RING).toInt()] = sample
            written++
        }
    }

    private val bins = FloatArray(FFT_SIZE / 2)
    private val waveform = FloatArray(AvsAudioFrame.SAMPLES)

    /**
     * Cuts this frame's window and runs the FFT; returns the magnitude bins
     * (reused array, 0..1, [FFT_SIZE]/2 of them) before [frame] stretches them
     * for the components.
     */
    fun capture(): FloatArray {
        latest(waveform)
        analyse(waveform)
        return bins
    }

    // handed out by reference and refilled per frame, so the render thread
    // allocates no FloatArray per frame
    private val outWave = FloatArray(AvsAudioFrame.SAMPLES)
    private val outSpectrum = FloatArray(AvsAudioFrame.SAMPLES)

    /** The frame the components see, from the last [capture]. The arrays are reused across frames. */
    fun frame(beat: Boolean): AvsAudioFrame {
        waveform.copyInto(outWave)
        stretchInto(outSpectrum)
        return AvsAudioFrame(outWave, outSpectrum, beat)
    }

    private fun latest(into: FloatArray) {
        // the newest sample lands at the end; what was never fed reads as silence
        val start = written - into.size
        val oldestKept = written - minOf(written, RING.toLong())
        for (i in into.indices) {
            val at = start + i
            into[i] = if (at >= oldestKept && at < written) ring[at.mod(RING.toLong()).toInt()] else 0f
        }
    }

    private fun analyse(waveform: FloatArray) {
        for (i in 0 until FFT_SIZE) {
            real[i] = (if (i < waveform.size) waveform[i] else 0f) * hann[i]
            imag[i] = 0f
        }
        fft(real, imag)
        for (i in bins.indices) {
            val linear = min(1f, sqrt(real[i] * real[i] + imag[i] * imag[i]) / (bins.size * NORMALISE))
            // AVS runs the spectrum through a log table before any component
            // sees it: 255*log(m*60/255+1)/log(60) - transcribed from
            // main.cpp's g_logtab (vis_avs, BSD; see NOTICE.md).
            bins[i] = LOG_TABLE[(linear * FULL_BYTE).toInt().coerceIn(0, FULL_BYTE)]
        }
    }

    /**
     * The bins stretched to 576, low frequency first.
     *
     * A 512-point FFT gives 256 bins and AVS components read 576, so each
     * output value reads the FFT bin at the same relative position.
     */
    private fun stretchInto(out: FloatArray) {
        for (i in out.indices) {
            out[i] = bins[i * (bins.size - 1) / (out.size - 1)]
        }
    }

    /** In-place radix-2 Cooley-Tukey. */
    private fun fft(
        re: FloatArray,
        im: FloatArray,
    ) {
        val n = re.size
        // bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
            var mask = n shr 1
            while (j and mask != 0) {
                j = j and mask.inv()
                mask = mask shr 1
            }
            j = j or mask
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = start + k + length / 2
                    val tRe = curRe * re[odd] - curIm * im[odd]
                    val tIm = curRe * im[odd] + curIm * re[odd]
                    re[odd] = re[even] - tRe
                    im[odd] = im[even] - tIm
                    re[even] += tRe
                    im[even] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    companion object {
        /** Half of this is how many bins [capture] answers with. */
        const val FFT_SIZE = 512

        private const val FULL_BYTE = 255

        /** AVS's spectrum curve, 0..1 in and out; see [analyse]. */
        private val LOG_TABLE =
            FloatArray(FULL_BYTE + 1) { x ->
                val a = kotlin.math.ln(x * 60.0 / FULL_BYTE + 1.0) / kotlin.math.ln(60.0)
                (a.coerceIn(0.0, 1.0)).toFloat()
            }
        const val RING = 2048
        const val HALF = 0.5

        /**
         * Scales a full-scale sine to full deflection: its FFT peak magnitude
         * is N/2 x amplitude x the Hann window's 0.5 = N/4 = 128, so dividing
         * by bins.size x 0.5 = 128 puts it at 1.0.
         */
        const val NORMALISE = 0.5f
    }
}
