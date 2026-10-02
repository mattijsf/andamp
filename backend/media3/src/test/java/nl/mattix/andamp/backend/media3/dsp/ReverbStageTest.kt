// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The reverb checked from outside: the room keeps sounding after the input
 * stops, it decays, and it stays bounded. Nothing here depends on the number
 * of combs.
 */
class ReverbStageTest {
    private val rate = 44_100

    private fun reverb(
        level: Float = 1f,
        size: Float = 0.5f,
        near: Float = 0.5f,
        air: Float = 0.5f,
        enabled: Boolean = true,
    ) = ReverbStage(rate).apply {
        update(
            DspSettings.Reverb(
                enabled = enabled,
                level = level,
                size = size,
                near = near,
                air = air,
            ),
        )
    }

    /** Feeds [input] in as stereo and returns the left channel. */
    private fun run(
        stage: ReverbStage,
        input: FloatArray,
    ): FloatArray {
        val frame = FloatArray(2)
        return FloatArray(input.size) {
            frame[0] = input[it]
            frame[1] = input[it]
            stage.process(frame)
            frame[0]
        }
    }

    private fun tone(
        samples: Int,
        hz: Float = 440f,
        amplitude: Float = 0.5f,
    ) = FloatArray(samples) { sin(2 * Math.PI * hz * it / rate).toFloat() * amplitude }

    private fun impulse(samples: Int) = FloatArray(samples).also { it[0] = 1f }

    private fun ms(count: Int) = rate * count / 1000

    private fun rms(
        values: FloatArray,
        from: Int = 0,
        until: Int = values.size,
    ): Float {
        var sum = 0.0
        for (i in from until until) sum += values[i].toDouble() * values[i]
        return sqrt(sum / (until - from)).toFloat()
    }

    /** How much the signal moves between samples against how loud it is: a simple brightness measure. */
    private fun brightness(tail: FloatArray): Float {
        val level = rms(tail)
        if (level == 0f) return 0f
        return rms(FloatArray(tail.size - 1) { tail[it + 1] - tail[it] }) / level
    }

    @Test
    fun `a switched-off reverb leaves every sample exactly as it was`() {
        val input = tone(4000)

        val out = run(reverb(enabled = false), input)

        assertArrayEquals("a disabled effect leaves the audio unchanged", input, out, 0f)
    }

    @Test
    fun `a fully dry mix comes back sample for sample`() {
        val input = tone(4000)

        val out = run(reverb(level = 0f), input)

        assertArrayEquals("level 0 leaves the audio unchanged", input, out, 0f)
    }

    @Test
    fun `the room keeps sounding after the input has stopped`() {
        val input = FloatArray(rate)
        tone(ms(100)).copyInto(input)

        val out = run(reverb(), input)

        val tail = rms(out, rate / 2, rate / 2 + 2000)
        assertTrue("the room still sounds half a second after the note ends: $tail", tail > 1e-3f)
    }

    @Test
    fun `the mix is audibly different from the dry signal it was made from`() {
        val input = tone(rate / 2)

        val out = run(reverb(level = 0.25f), input)

        val change = rms(FloatArray(input.size) { out[it] - input[it] })
        assertTrue("a quarter of wet signal changes the output measurably: $change", change > 0.01f)
    }

    @Test
    fun `an impulse decays towards silence instead of growing`() {
        val seconds = 4

        val out = run(reverb(size = 1f), impulse(rate * seconds))

        val first = rms(out, 0, rate / 2)
        val middle = rms(out, rate, rate + rate / 2)
        val last = rms(out, rate * (seconds - 1), rate * (seconds - 1) + rate / 2)
        assertTrue("the tail falls over the first second: $first -> $middle", middle < first)
        assertTrue("the tail falls over the last three seconds: $middle -> $last", last < middle)
        assertTrue("the biggest room is quiet four seconds on: $last", last < 1e-4f)
    }

    @Test
    fun `every combination of the sliders decays and stays inside the headroom`() {
        val steps = listOf(0f, 0.5f, 1f)

        for (size in steps) {
            for (near in steps) {
                for (air in steps) {
                    val out = run(reverb(size = size, near = near, air = air), impulse(rate * 2))

                    val where = "size=$size near=$near air=$air"
                    assertTrue("$where produces only finite samples", out.all { it.isFinite() })
                    assertTrue("$where stays inside the headroom: ${out.maxOf { abs(it) }}", out.all { abs(it) <= 1.5f })
                    val early = rms(out, 0, ms(250))
                    val late = rms(out, ms(1500), ms(1750))
                    assertTrue("$where decays: $early -> $late", late < early * 0.05f)
                }
            }
        }
    }

    @Test
    fun `a long loud run stays bounded and finite`() {
        val loud = tone(rate * 5, hz = 110f, amplitude = 0.99f)

        val out = run(reverb(size = 1f, near = 1f, air = 1f), loud)

        assertTrue("five seconds of loud bass stay finite", out.all { it.isFinite() })
        assertTrue("the output stays inside the headroom: ${out.maxOf { abs(it) }}", out.all { abs(it) <= 1.5f })
    }

    @Test
    fun `full-scale DC, the loudest thing a comb bank can be fed, stays inside the headroom`() {
        val out = run(reverb(size = 1f, near = 1f, air = 1f), FloatArray(rate * 4) { 1f })

        assertTrue("the worst-case input stays finite", out.all { it.isFinite() })
        assertTrue("the worst case stays inside the headroom: ${out.maxOf { abs(it) }}", out.all { abs(it) <= 1.5f })
    }

    @Test
    fun `a non-finite sample does not poison the room behind it`() {
        val stage = reverb()
        val bad = FloatArray(2)
        repeat(1000) {
            bad[0] = Float.NaN
            bad[1] = Float.POSITIVE_INFINITY
            stage.process(bad)
        }

        val out = run(stage, tone(rate))

        assertTrue("the output after bad samples is finite", out.all { it.isFinite() })
    }

    @Test
    fun `sliders outside 0 to 1 keep the output finite and bounded`() {
        val wild =
            ReverbStage(rate).apply {
                update(DspSettings.Reverb(enabled = true, level = 12f, size = -4f, near = 9f, air = -2f))
            }

        val out = run(wild, tone(rate * 2, amplitude = 0.99f))

        assertTrue("out-of-range settings stay finite", out.all { it.isFinite() })
        assertTrue("out-of-range settings stay inside the headroom: ${out.maxOf { abs(it) }}", out.all { abs(it) <= 1.5f })
    }

    @Test
    fun `reset drops the tail`() {
        val stage = reverb()
        run(stage, tone(rate))

        stage.reset()
        val out = run(stage, FloatArray(2000))

        assertTrue("nothing of the previous audio comes through after the reset", out.all { it == 0f })
    }

    @Test
    fun `a mono stream is processed without reaching for a second channel`() {
        val stage = reverb()
        val input = tone(rate)
        val frame = FloatArray(1)

        val out =
            FloatArray(input.size) {
                frame[0] = input[it]
                stage.process(frame)
                frame[0]
            }

        assertTrue("mono comes out finite and in range", out.all { it.isFinite() && abs(it) <= 1.5f })
        assertTrue("a mono stream gets a reverb tail", rms(out, rate / 2, rate / 2 + 2000) > 1e-3f)
    }

    @Test
    fun `a hall rings longer than a small room`() {
        val small = run(reverb(size = 0f), impulse(rate * 2))
        val hall = run(reverb(size = 1f), impulse(rate * 2))

        val smallTail = rms(small, rate, rate + rate / 4)
        val hallTail = rms(hall, rate, rate + rate / 4)
        assertTrue("the hall is still audible a second on: $hallTail", hallTail > 1e-6f)
        assertTrue("the hall rings longer than the small room: $smallTail vs $hallTail", hallTail > smallTail * 10f)
    }

    @Test
    fun `more air keeps more top end in the tail`() {
        val dark = run(reverb(air = 0f, near = 0f), impulse(rate))
        val bright = run(reverb(air = 1f, near = 0f), impulse(rate))

        val darkTail = brightness(dark.copyOfRange(rate / 4, rate / 2))
        val brightTail = brightness(bright.copyOfRange(rate / 4, rate / 2))
        assertTrue("less air darkens the tail: $darkTail vs $brightTail", brightTail > darkTail)
    }

    @Test
    fun `turning near up brings the early reflections forward`() {
        val distant = run(reverb(near = 0f), impulse(rate))
        val close = run(reverb(near = 1f), impulse(rate))

        val distantRatio = rms(distant, 0, ms(60)) / rms(distant, ms(500), ms(600))
        val closeRatio = rms(close, 0, ms(60)) / rms(close, ms(500), ms(600))
        assertTrue("near brings the first reflections forward: $distantRatio vs $closeRatio", closeRatio > distantRatio)
    }

    @Test
    fun `switching off drops the tail instead of parking it for later`() {
        val stage = ReverbStage(rate)
        stage.update(DspSettings.Reverb(enabled = true, level = 1f, size = 0.9f))
        // fill the network, then switch off mid-tail
        repeat(rate / 2) { n ->
            stage.process(floatArrayOf(if (n < 100) 0.8f else 0f, if (n < 100) 0.8f else 0f))
        }
        stage.update(DspSettings.Reverb(enabled = false))
        repeat(100) { stage.process(floatArrayOf(0f, 0f)) }

        // switched back on later, silence in must give silence out
        stage.update(DspSettings.Reverb(enabled = true, level = 1f, size = 0.9f))
        var peak = 0f
        repeat(2000) {
            val frame = floatArrayOf(0f, 0f)
            stage.process(frame)
            peak = maxOf(peak, kotlin.math.abs(frame[0]), kotlin.math.abs(frame[1]))
        }

        assertTrue("the old tail does not resume when the effect comes back: $peak", peak < 1e-4f)
    }
}
