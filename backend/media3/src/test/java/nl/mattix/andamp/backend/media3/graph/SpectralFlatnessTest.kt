// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * That the flatness measure says what it claims.
 *
 * It is held against signals whose answer is arithmetic, a pitch and noise,
 * and has to separate them by a wide margin before it is used on a reverb
 * tail.
 */
class SpectralFlatnessTest {
    private val rate = 44_100
    private val window = 4_096

    private fun sine(
        hz: Double,
        length: Int = window,
    ) = FloatArray(length) { sin(2.0 * PI * hz * it / rate).toFloat() }

    /** A fixed seed, so the measurement is the same on every run. */
    private fun noise(length: Int = window): FloatArray {
        val random = Random(20_260_831L)
        return FloatArray(length) { (random.nextDouble() * 2.0 - 1.0).toFloat() }
    }

    @Test
    fun `one pitch is not flat`() {
        val flatness = Spectrum.flatness(sine(1_000.0))

        assertTrue("a lone sine measures below 0.01: $flatness", flatness < 0.01)
    }

    @Test
    fun `noise is flat`() {
        // white noise through a periodogram lands near exp(-euler), about 0.56,
        // and not at 1: each bin is one draw, not the average of many
        val flatness = Spectrum.flatness(noise())

        assertTrue("white noise measures above 0.3: $flatness", flatness > 0.3)
    }

    @Test
    fun `the measure separates a pitch from noise by a wide margin`() {
        // the margin the measure needs before it can say anything about a tail
        val pitch = Spectrum.flatness(sine(1_000.0))
        val noise = Spectrum.flatness(noise())

        assertTrue("pitch $pitch against noise $noise", noise > pitch * 50)
    }

    @Test
    fun `a handful of pitches is still not noise`() {
        // a ringing reverb is a few strong modes, not one tone; these must not
        // read as flat
        val ringing =
            FloatArray(window) { at ->
                listOf(400.0, 940.0, 1_610.0, 2_270.0)
                    .sumOf { sin(2.0 * PI * it * at / rate) }
                    .toFloat() / 4f
            }

        val flatness = Spectrum.flatness(ringing)
        assertTrue("four pitches measure below 0.05: $flatness", flatness < 0.05)
    }

    @Test
    fun `flatness stays within its own bounds`() {
        listOf(sine(1_000.0), noise(), FloatArray(window), FloatArray(window) { 1f }).forEach {
            val flatness = Spectrum.flatness(it)
            assertTrue("flatness lies within 0..1: $flatness", flatness in 0.0..1.0)
        }
    }

    @Test
    fun `silence measures zero`() {
        assertEquals(0.0, Spectrum.flatness(FloatArray(window)), 0.0)
    }

    @Test
    fun `the level a signal is at does not change how flat it is`() {
        // the reverb tail decays while it is measured, so the measure must not
        // move with amplitude
        val loud = Spectrum.flatness(noise())
        val quiet = Spectrum.flatness(noise().map { it * 0.001f }.toFloatArray())

        assertEquals(loud, quiet, 1e-6)
    }

    /** Noise with its top rolled off. */
    private fun darkNoise(): FloatArray {
        val raw = noise()
        var held = 0f
        return FloatArray(raw.size) {
            held += 0.02f * (raw[it] - held)
            held
        }
    }

    @Test
    fun `flatness over the whole spectrum follows brightness and not only pitch`() {
        // Dark noise has no pitch in it, yet across the whole spectrum it
        // scores like something that does, because the empty top half pulls
        // the geometric mean down.
        val white = Spectrum.flatness(noise())
        val dark = Spectrum.flatness(darkNoise())

        assertTrue("white $white against dark $dark", dark < white / 10)
    }

    @Test
    fun `within one band, dark noise measures as noise`() {
        // inside a band both are noise and both measure as noise, so darkening
        // a signal is not read as removing a pitch from it
        val white = Spectrum.bandFlatness(noise(), rate, LOW_BAND, HIGH_BAND)
        val dark = Spectrum.bandFlatness(darkNoise(), rate, LOW_BAND, HIGH_BAND)

        assertTrue("white $white against dark $dark", dark > white / 2)
        assertTrue("dark noise in band measures above 0.3: $dark", dark > 0.3)
    }

    @Test
    fun `a band still hears a pitch inside it`() {
        // the band measure must still tell a pitch from noise
        val pitch = Spectrum.bandFlatness(sine(1_000.0), rate, LOW_BAND, HIGH_BAND)
        val noise = Spectrum.bandFlatness(noise(), rate, LOW_BAND, HIGH_BAND)

        assertTrue("pitch $pitch against noise $noise", noise > pitch * 20)
    }

    private companion object {
        const val LOW_BAND = 500.0
        const val HIGH_BAND = 2_000.0
    }
}
