// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The modulation stage checked by its effect on the signal: each mode changes
 * it, the modes differ, it stays bounded over a long run, and it starts from
 * silence after a reset.
 */
class ModulationStageTest {
    private val sampleRate = 44_100

    @Test
    fun `a switched off effect leaves every sample exactly as it was`() {
        val stage = ModulationStage(sampleRate)
        stage.update(settings(DspSettings.Mode.FLANGER).copy(enabled = false))
        val input = tone(sampleRate / 4)

        val out = run(stage, input)

        assertArrayEquals("a disabled stage leaves the audio unchanged", input, out[0], 0f)
    }

    @Test
    fun `level 0 passes the audio through untouched`() {
        val stage = ModulationStage(sampleRate)
        stage.update(settings(DspSettings.Mode.CHORUS, level = 0f))
        val input = tone(sampleRate / 4)

        val out = run(stage, input)

        assertArrayEquals("a mix with no wet signal returns the input", input, out[0], 0f)
    }

    @Test
    fun `FLANGER colors the signal`() {
        val stage = ModulationStage(sampleRate)
        stage.update(settings(DspSettings.Mode.FLANGER))
        val input = tone(sampleRate)

        val out = run(stage, input)

        assertTrue("the flanger changes the signal", difference(input, out[0]) > 0.01)
    }

    @Test
    fun `CHORUS colors the signal`() {
        val stage = ModulationStage(sampleRate)
        stage.update(settings(DspSettings.Mode.CHORUS))
        val input = tone(sampleRate)

        val out = run(stage, input)

        assertTrue("the detuned copy reaches the output", difference(input, out[0]) > 0.01)
    }

    @Test
    fun `PHASER colors the signal`() {
        val stage = ModulationStage(sampleRate)
        stage.update(settings(DspSettings.Mode.PHASER))
        val input = tone(sampleRate)

        val out = run(stage, input)

        assertTrue("the all-pass chain changes the signal", difference(input, out[0]) > 0.01)
    }

    @Test
    fun `CHORUS and FLANGER do not sound the same at identical settings`() {
        val input = tone(sampleRate)
        val chorus = ModulationStage(sampleRate).also { it.update(settings(DspSettings.Mode.CHORUS)) }
        val flanger = ModulationStage(sampleRate).also { it.update(settings(DspSettings.Mode.FLANGER)) }

        val chorused = run(chorus, input)[0]
        val flanged = run(flanger, input)[0]

        assertTrue("chorus and flanger give different outputs", difference(chorused, flanged) > 0.01)
    }

    @Test
    fun `every mode stays bounded through a long run at full depth and feedback`() {
        for (mode in DspSettings.Mode.entries) {
            val stage = ModulationStage(sampleRate)
            stage.update(settings(mode, level = 1f, depth = 1f, rate = 1f, feedback = 1f))

            val out = run(stage, tone(sampleRate * 4))[0]

            assertTrue("$mode stays finite and within 1.5: ${out.max()}", out.all { it.isFinite() && abs(it) <= 1.5f })
        }
    }

    @Test
    fun `reset drops the tail`() {
        for (mode in DspSettings.Mode.entries) {
            val stage = ModulationStage(sampleRate)
            stage.update(settings(mode, level = 1f, feedback = 1f))
            run(stage, tone(sampleRate))

            stage.reset()
            val out = run(stage, FloatArray(sampleRate / 10))[0]

            assertTrue("$mode passes nothing of the previous audio after a reset", out.all { it == 0f })
        }
    }

    @Test
    fun `mono is processed without a stereo partner`() {
        val stage = ModulationStage(sampleRate)
        stage.update(settings(DspSettings.Mode.FLANGER, stereo = 1f))
        val input = tone(sampleRate / 2)

        val out = run(stage, input, channels = 1)

        assertTrue("mono stays finite under a stereo spread", out[0].all { it.isFinite() })
        assertTrue("mono still gets the effect", difference(input, out[0]) > 0.01)
    }

    @Test
    fun `stereo pushes the two channels' sweeps apart`() {
        val input = tone(sampleRate)
        val together = ModulationStage(sampleRate).also { it.update(settings(DspSettings.Mode.FLANGER, stereo = 0f)) }
        val apart = ModulationStage(sampleRate).also { it.update(settings(DspSettings.Mode.FLANGER, stereo = 1f)) }

        val inPhase = run(together, input)
        val opposed = run(apart, input)

        assertArrayEquals("without spread both channels sweep together", inPhase[0], inPhase[1], 0f)
        assertTrue("half a cycle of spread makes the channels differ", difference(opposed[0], opposed[1]) > 0.01)
    }

    @Test
    fun `a stray non-finite sample does not poison the effect`() {
        val stage = ModulationStage(sampleRate)
        stage.update(settings(DspSettings.Mode.FLANGER, level = 1f, feedback = 1f))
        val input = tone(sampleRate / 2)
        input[100] = Float.NaN
        input[101] = Float.POSITIVE_INFINITY

        val out = run(stage, input)[0]

        assertTrue("a NaN in the input does not stay in the feedback loop", out.all { it.isFinite() })
        assertTrue("the effect recovers after the bad samples", out.drop(1000).any { it != 0f })
    }

    private fun settings(
        mode: DspSettings.Mode,
        level: Float = 0.5f,
        lfo: Float = 0f,
        depth: Float = 0.5f,
        rate: Float = 0.25f,
        feedback: Float = 0.4f,
        stereo: Float = 0.5f,
    ) = DspSettings.Modulation(
        enabled = true,
        mode = mode,
        level = level,
        lfo = lfo,
        depth = depth,
        rate = rate,
        feedback = feedback,
        stereo = stereo,
    )

    private fun tone(
        samples: Int,
        hz: Float = 440f,
    ) = FloatArray(samples) { sin(2 * Math.PI * hz * it / sampleRate).toFloat() * 0.5f }

    /** Runs [input] through [stage] with the same sample in every channel, and returns each channel's output. */
    private fun run(
        stage: ModulationStage,
        input: FloatArray,
        channels: Int = 2,
    ): Array<FloatArray> {
        val out = Array(channels) { FloatArray(input.size) }
        val frame = FloatArray(channels)
        for (index in input.indices) {
            frame.fill(input[index])
            stage.process(frame)
            for (channel in 0 until channels) out[channel][index] = frame[channel]
        }
        return out
    }

    /** The RMS difference between two signals. */
    private fun difference(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var sum = 0.0
        for (index in a.indices) {
            val delta = (a[index] - b[index]).toDouble()
            sum += delta * delta
        }
        return sqrt(sum / a.size)
    }

    @Test
    fun `the phaser notches where its sweep is, not mirrored around Nyquist`() {
        // an all-pass coefficient of the wrong sign puts the notch at pi - w_c,
        // so sweeping up moves it down, which the other tests here do not detect
        val low = notchDepthAt(hz = 220f)
        val high = notchDepthAt(hz = 6000f)

        assertTrue(
            "with the sweep at its floor the low tone is the notched one: low=$low high=$high",
            low > high,
        )
    }

    /** How much a static phaser (rate and depth at zero) takes off a tone at [hz]. */
    private fun notchDepthAt(hz: Float): Float {
        val stage =
            ModulationStage(sampleRate).apply {
                configure(sampleRate, 2)
                update(
                    DspSettings.Modulation(
                        enabled = true,
                        mode = DspSettings.Mode.PHASER,
                        level = 1f,
                        depth = 0f,
                        rate = 0f,
                        feedback = 0f,
                        stereo = 0f,
                    ),
                )
            }
        var dry = 0.0
        var wet = 0.0
        repeat(sampleRate / 4) { n ->
            val sample = kotlin.math.sin(2 * Math.PI * hz * n / sampleRate).toFloat() * 0.5f
            val frame = floatArrayOf(sample, sample)
            stage.process(frame)
            // ignore the first few ms while the all-passes settle
            if (n > sampleRate / 20) {
                dry += (sample * sample).toDouble()
                wet += (frame[0] * frame[0]).toDouble()
            }
        }
        return (dry / wet.coerceAtLeast(1e-12)).toFloat()
    }
}
