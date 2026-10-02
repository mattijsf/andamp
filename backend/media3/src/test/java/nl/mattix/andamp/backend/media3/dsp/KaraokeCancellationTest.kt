// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import nl.mattix.andamp.core.model.BuiltInEffects
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How much of the singer goes, in decibels, at the app's default settings.
 *
 * [KaraokeStageTest] sets `width = 0` in each of its cancellation cases. This
 * measures the defaults, and what a width above zero does to the cancellation.
 *
 * Measurement is per harmonic by correlation, because a broadband level does
 * not say which part of the voice survived.
 */
class KaraokeCancellationTest {
    private val rate = 44_100

    /** Amplitude of [hz] in [samples], by correlation with a complex exponential. */
    private fun level(
        samples: FloatArray,
        hz: Double,
    ): Double {
        var re = 0.0
        var im = 0.0
        samples.forEachIndexed { n, v ->
            re += v * cos(2 * PI * hz * n / rate)
            im += v * sin(2 * PI * hz * n / rate)
        }
        return 2 * sqrt(re * re + im * im) / samples.size
    }

    private fun db(ratio: Double) = 20 * log10(ratio.coerceAtLeast(FLOOR))

    /**
     * A centered voice of [VOICE_HARMONICS] over a hard-panned guitar, through a
     * stage set up from [width]. Returns the decibels each harmonic lost, and
     * the decibels the guitar lost.
     */
    private fun cancellation(
        width: Float,
        band: Float = BAND,
    ): Pair<List<Double>, Double> {
        val stage =
            KaraokeStage(rate).apply {
                update(DspSettings.Karaoke(enabled = true, level = 1f, filter = FILTER, band = band, width = width))
            }
        val dry = FloatArray(rate)
        val wet = FloatArray(rate)
        val frame = FloatArray(2)
        for (n in 0 until rate) {
            val voice = VOICE_HARMONICS.sumOf { sin(2 * PI * it * n / rate) / VOICE_HARMONICS.size }.toFloat() * 0.5f
            val guitar = sin(2 * PI * GUITAR_HZ * n / rate).toFloat() * 0.3f
            frame[0] = voice + guitar
            frame[1] = voice
            dry[n] = frame[0]
            stage.process(frame)
            wet[n] = frame[0]
        }
        return VOICE_HARMONICS.map { db(level(wet, it) / level(dry, it)) } to
            db(level(wet, GUITAR_HZ) / level(dry, GUITAR_HZ))
    }

    @Test
    fun `at the default settings the voice is removed and a hard-panned part stays`() {
        val shipped = BuiltInEffects.karaoke.defaults

        val (voice, guitar) = cancellation(width = shipped["width", 1f], band = shipped["band", BAND])

        // above the kept low end, which is the part a listener hears as the voice
        val above = voice.drop(1)
        assertTrue(
            "the voice above the kept low end is at least 20 dB down: ${above.max().toInt()} dB, $voice",
            above.max() < WANTED_DB,
        )
        assertTrue("a hard-panned part loses less than 7 dB: $guitar", guitar > KEPT_DB)
    }

    @Test
    fun `any width sets a floor on the cancellation`() {
        val (none, _) = cancellation(width = 0f)
        val (aTenth, _) = cancellation(width = 0.1f)
        val (theOldDefault, _) = cancellation(width = 0.3f)

        // Measured at the top harmonic: with no width the cancellation deepens
        // with frequency, and with any width it flattens out, because what is
        // mixed back contains the voice. Lower down the kept bass dominates.
        assertTrue("with no width the top harmonic is more than 40 dB down: $none", none.last() < -40)
        assertTrue("a tenth of width raises the floor by more than 20 dB: $aTenth vs $none", aTenth.last() > none.last() + 20)
        assertTrue("a width of 0.3 leaves the top harmonic less than 20 dB down: $theOldDefault", theOldDefault.last() > -20)
    }

    private companion object {
        /** A voice is a fundamental and its harmonics, not a sine. */
        val VOICE_HARMONICS = listOf(150.0, 300.0, 450.0, 900.0, 1800.0, 3000.0)
        const val GUITAR_HZ = 700.0
        const val FILTER = 0.35f
        const val BAND = 0.5f

        /** The loss that counts as removed. */
        const val WANTED_DB = -20.0

        /** The loss a hard-panned part may take. Halving is inherent to a mid/side rebuild. */
        const val KEPT_DB = -7.0

        const val FLOOR = 1e-9
    }
}
