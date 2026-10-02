// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the resampler does to the sound: a tone the output can carry keeps its level, a
 * tone it cannot carry does not come back as an alias, and the result does not depend on
 * how the stream is sliced.
 *
 * Levels are read with a single Hann-windowed Fourier coefficient at the frequency in
 * question, over a stretch past the filter's lead-in.
 */
class PcmRateFilterTest {
    @Test
    fun `a 1 kHz tone at 48 kHz comes through at its own level`() {
        val out = convert(PcmRate(from = 48_000), tone(1_000.0, 48_000, SECOND_48))

        val gain = decibels(level(out, 44_100, 1_000.0))

        println("1 kHz, 48 -> 44.1 kHz: ${"%.4f".format(gain)} dB")
        assertEquals(0.0, gain, 0.1)
    }

    @Test
    fun `a 23 kHz tone at 48 kHz does not fold back into the audible band`() {
        val out = convert(PcmRate(from = 48_000), tone(23_000.0, 48_000, SECOND_48))

        // 23 kHz cannot exist at 44.1 kHz; its alias would be its reflection about
        // 22.05 kHz, at 21.1 kHz
        val alias = decibels(level(out, 44_100, 44_100 - 23_000.0))

        println("23 kHz alias at 21.1 kHz, 48 -> 44.1 kHz: ${"%.1f".format(alias)} dB")
        assertTrue("the alias is at least 60 dB down: $alias dB", alias <= -60.0)
    }

    @Test
    fun `a rate with no small common step still keeps its alias out`() {
        // 47999 and 44100 share only a factor of seven, so the exact step would need 6300
        // phases: this uses the table with blended rows
        val out = convert(PcmRate(from = 47_999), tone(23_000.0, 47_999, 47_999))

        val alias = decibels(level(out, 44_100, 44_100 - 23_000.0))
        val tone = decibels(level(convert(PcmRate(from = 47_999), tone(1_000.0, 47_999, 47_999)), 44_100, 1_000.0))

        println("47.999 -> 44.1 kHz: 1 kHz ${"%.4f".format(tone)} dB, 23 kHz alias ${"%.1f".format(alias)} dB")
        assertEquals(0.0, tone, 0.1)
        assertTrue("the alias is at least 60 dB down: $alias dB", alias <= -60.0)
    }

    @Test
    fun `upsampling from 22 kHz keeps the tone and drops its image`() {
        val out = convert(PcmRate(from = 22_050), tone(5_000.0, 22_050, 22_050))

        // doubling the rate without a filter leaves an image of every tone on the far side
        // of the old Nyquist: 5 kHz with an image at 17.05 kHz
        val kept = decibels(level(out, 44_100, 5_000.0))
        val image = decibels(level(out, 44_100, 22_050 - 5_000.0))

        println("22.05 -> 44.1 kHz: 5 kHz ${"%.4f".format(kept)} dB, image at 17.05 kHz ${"%.1f".format(image)} dB")
        assertEquals(0.0, kept, 0.1)
        assertTrue("the image is at least 60 dB down: $image dB", image <= -60.0)
    }

    @Test
    fun `a constant stays that constant`() {
        for (from in RATES) {
            val source = ShortArray(from * 2) { index -> if (index % 2 == 0) DC_LEFT else DC_RIGHT }
            val out = convert(PcmRate(from = from), source)

            // past the lead-in every row of the table sums to one, so a steady level in is
            // the same level out, to within one step
            for (index in SETTLED_FRAMES * 2 until out.size) {
                val want = if (index % 2 == 0) DC_LEFT else DC_RIGHT
                assertTrue("$from Hz holds its level at $index: ${out[index]}", abs(out[index] - want) <= 1)
            }
        }
    }

    @Test
    fun `the way a stream is sliced does not change a sample of it`() {
        for (from in RATES) {
            val source = noise(from / 10)
            val whole = convert(PcmRate(from = from), source, blockFrames = source.size / 2)

            // one frame at a time exercises the history carried across every block boundary
            for (block in listOf(1, 7, 480)) {
                assertArrayEquals("$from Hz in blocks of $block", whole, convert(PcmRate(from = from), source, block))
            }
        }
    }

    @Test
    fun `a seek leaves nothing of the old stream in the filter`() {
        for (from in RATES) {
            val rate = PcmRate(from = from)
            convert(rate, noise(from / 10))
            rate.reset()
            val after = convert(rate, noise(from / 10, seed = 7))

            // after a reset the filter's history is silence, as for a stream just opened
            assertArrayEquals("$from Hz", convert(PcmRate(from = from), noise(from / 10, seed = 7)), after)
        }
    }

    @Test
    fun `a file already at 44 point 1 is copied untouched`() {
        val source = noise(4_410)
        val out = convert(PcmRate(from = 44_100), source)

        assertArrayEquals(source, out)
    }

    @Test
    fun `how fast it runs`() {
        val source = tone(1_000.0, 48_000, SECOND_48)
        val rate = PcmRate(from = 48_000)
        val block = ShortArray(480 * 2)
        val into = ShortArray(rate.room(block.size))
        repeat(WARM_UP_SECONDS) { run(rate, source, block, into) }

        val started = System.nanoTime()
        repeat(TIMED_SECONDS) { run(rate, source, block, into) }
        val seconds = (System.nanoTime() - started) / 1e9

        // logged and not asserted: the speed depends on the machine
        println("48 -> 44.1 kHz stereo: ${"%.0f".format(TIMED_SECONDS / seconds)}x realtime on this JVM")
    }

    /** One second of [source] fed through [rate] in codec-sized blocks. */
    private fun run(
        rate: PcmRate,
        source: ShortArray,
        block: ShortArray,
        into: ShortArray,
    ) {
        var at = 0
        while (at < source.size) {
            val count = minOf(block.size, source.size - at)
            System.arraycopy(source, at, block, 0, count)
            rate.convert(block, count, into)
            at += count
        }
    }

    /** All of [source] through [rate], [blockFrames] frames at a time, gathered into one array. */
    private fun convert(
        rate: PcmRate,
        source: ShortArray,
        blockFrames: Int = 480,
    ): ShortArray {
        val out = ShortArray(rate.room(source.size) + source.size)
        val block = ShortArray(blockFrames * 2)
        val into = ShortArray(rate.room(block.size))
        var read = 0
        var written = 0
        while (read < source.size) {
            val count = minOf(block.size, source.size - read)
            System.arraycopy(source, read, block, 0, count)
            val made = rate.convert(block, count, into)
            System.arraycopy(into, 0, out, written, made)
            read += count
            written += made
        }
        return out.copyOf(written)
    }

    /** [frames] of a sine at [hertz], left at [AMPLITUDE] and right its inverse, so a channel swap would show. */
    private fun tone(
        hertz: Double,
        sampleRate: Int,
        frames: Int,
    ): ShortArray =
        ShortArray(frames * 2) { index ->
            val value = (AMPLITUDE * sin(2 * PI * hertz * (index / 2) / sampleRate)).roundToInt()
            (if (index % 2 == 0) value else -value).toShort()
        }

    /** [frames] of repeatable white noise, which has every frequency in it. */
    private fun noise(
        frames: Int,
        seed: Long = 1L,
    ): ShortArray {
        var state = seed
        return ShortArray(frames * 2) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            (state ushr 48).toInt().toShort()
        }
    }

    /**
     * The amplitude of [hertz] in the left channel of [samples] at [sampleRate],
     * relative to [AMPLITUDE], from a Hann-windowed coefficient over a settled
     * stretch.
     */
    private fun level(
        samples: ShortArray,
        sampleRate: Int,
        hertz: Double,
    ): Double {
        val frames = samples.size / 2
        val count = minOf(WINDOW_FRAMES, frames - SETTLED_FRAMES * 2)
        var real = 0.0
        var imaginary = 0.0
        var weight = 0.0
        for (n in 0 until count) {
            val window = 0.5 - 0.5 * cos(2 * PI * n / (count - 1))
            val x = samples[(SETTLED_FRAMES + n) * 2] * window
            val phase = 2 * PI * hertz * n / sampleRate
            real += x * cos(phase)
            imaginary += x * sin(phase)
            weight += window
        }
        return 2 * hypot(real, imaginary) / weight / AMPLITUDE
    }

    private fun decibels(ratio: Double): Double = 20 * log10(ratio.coerceAtLeast(1e-12))

    private companion object {
        const val AMPLITUDE = 16_000.0
        const val SECOND_48 = 48_000

        /** Past the lead-in of every filter here, which reaches at most a couple of hundred frames. */
        const val SETTLED_FRAMES = 512
        const val WINDOW_FRAMES = 32_768

        const val DC_LEFT: Short = 12_345
        const val DC_RIGHT: Short = -20_000

        /** Exact phases down, exact phases up, and a ratio that needs its phases interpolated. */
        val RATES = listOf(48_000, 22_050, 47_999)

        const val WARM_UP_SECONDS = 5
        const val TIMED_SECONDS = 20
    }
}
