// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Karaoke: what both channels share is taken out, what is panned stays, and
 * the low end is kept.
 */
class KaraokeStageTest {
    private val rate = 44_100

    private fun stage(
        level: Float = 1f,
        filter: Float = 0.35f,
        band: Float = 0.5f,
        width: Float = 0f,
        enabled: Boolean = true,
    ) = KaraokeStage(rate).apply {
        update(
            DspSettings.Karaoke(enabled = enabled, level = level, filter = filter, band = band, width = width),
        )
    }

    /** A tone at [hz] placed in the image by its amplitude in each channel. */
    private fun tone(
        seconds: Float,
        hz: Float,
        left: Float,
        right: Float,
    ) = List((rate * seconds).toInt()) {
        val v = sin(2 * Math.PI * hz * it / rate).toFloat()
        floatArrayOf(v * left, v * right)
    }

    private fun run(
        stage: KaraokeStage,
        frames: List<FloatArray>,
    ) = frames.map { it.copyOf().also(stage::process) }

    /** Loudness over the back half, past the filter settling on the front half. */
    private fun loudness(frames: List<FloatArray>): Double {
        val settled = frames.drop(frames.size / 2)
        return sqrt(settled.sumOf { (it[0] * it[0] + it[1] * it[1]).toDouble() } / settled.size)
    }

    @Test
    fun `switched off it hands the audio back sample for sample`() {
        val input = tone(seconds = 0.05f, hz = 440f, left = 0.5f, right = 0.5f)

        val out = run(stage(enabled = false), input)

        input.indices.forEach { assertArrayEquals("frame $it is untouched", input[it], out[it], 0f) }
    }

    @Test
    fun `level zero is the original audio, however the other sliders stand`() {
        val input = tone(seconds = 0.05f, hz = 440f, left = 0.5f, right = 0.2f)

        val out = run(stage(level = 0f, filter = 0.9f, band = 0f, width = 1f), input)

        input.indices.forEach { assertArrayEquals("frame $it is untouched", input[it], out[it], 0f) }
    }

    @Test
    fun `a voice panned dead center is cancelled far harder than one panned hard left`() {
        val centre = tone(seconds = 0.5f, hz = 1000f, left = 0.5f, right = 0.5f)
        val hardLeft = tone(seconds = 0.5f, hz = 1000f, left = 0.5f, right = 0f)

        val centreKept = loudness(run(stage(), centre)) / loudness(centre)
        val leftKept = loudness(run(stage(), hardLeft)) / loudness(hardLeft)

        assertTrue("the centered voice is cancelled: $centreKept of it is left", centreKept < 0.05)
        assertTrue("a hard-panned part stays: $leftKept", leftKept > 0.5)
        assertTrue("the center is cancelled ten times harder than the left: $centreKept vs $leftKept", centreKept < leftKept / 10)
    }

    @Test
    fun `the kick and the bass survive the cancellation`() {
        val bass = tone(seconds = 0.5f, hz = 60f, left = 0.5f, right = 0.5f)
        val voice = tone(seconds = 0.5f, hz = 1000f, left = 0.5f, right = 0.5f)

        val bassKept = loudness(run(stage(), bass)) / loudness(bass)
        val voiceKept = loudness(run(stage(), voice)) / loudness(voice)

        assertTrue("mono bass survives: $bassKept", bassKept > 0.7)
        assertTrue("the bass outlives the voice: $bassKept vs $voiceKept", bassKept > voiceKept * 10)
    }

    @Test
    fun `the filter slider decides how much of the low end comes back`() {
        val lowMid = tone(seconds = 0.5f, hz = 300f, left = 0.5f, right = 0.5f)

        val low = loudness(run(stage(filter = 0.1f), lowMid))
        val high = loudness(run(stage(filter = 1f), lowMid))

        assertTrue("a 500 Hz corner keeps more of a 300 Hz tone than a 74 Hz one: $high vs $low", high > low * 3)
    }

    @Test
    fun `the band slider widens the kept skirt into the low mids`() {
        val lowMid = tone(seconds = 0.5f, hz = 300f, left = 0.5f, right = 0.5f)

        val steep = loudness(run(stage(band = 0f), lowMid))
        val wide = loudness(run(stage(band = 1f), lowMid))

        assertTrue("12 dB/oct lets more through than 24: $wide vs $steep", wide > steep * 2)
    }

    @Test
    fun `width mixes the original stereo back over the cancellation`() {
        val centre = tone(seconds = 0.5f, hz = 1000f, left = 0.5f, right = 0.5f)

        val thin = loudness(run(stage(width = 0f), centre))
        val wide = loudness(run(stage(width = 1f), centre))

        assertTrue("width mixes the dry signal back: $thin vs $wide", wide > thin * 5)
        assertTrue("width does not undo the cancellation: $wide", wide < loudness(centre) * 0.6)
    }

    @Test
    fun `half level leaves about half the voice`() {
        val centre = tone(seconds = 0.5f, hz = 1000f, left = 0.5f, right = 0.5f)

        val half = loudness(run(stage(level = 0.5f), centre)) / loudness(centre)

        assertTrue("half a cancellation leaves about half the voice: $half", half in 0.4..0.6)
    }

    @Test
    fun `a long run stays finite and inside the ceiling`() {
        val karaoke = stage(band = 1f, width = 0.5f)
        val frame = FloatArray(2)
        var worst = 0f

        repeat(rate * 4) { n ->
            // near-antiphase at full scale: the worst case for a signal built on L-R
            frame[0] = sin(2 * Math.PI * 55.0 * n / rate).toFloat()
            frame[1] = -sin(2 * Math.PI * 55.5 * n / rate).toFloat()
            karaoke.process(frame)
            worst = max(worst, max(abs(frame[0]), abs(frame[1])))
        }

        assertTrue("the output stays finite after four seconds: $worst", worst.isFinite())
        assertTrue("the output stays within the ceiling: $worst", worst <= 1.5f)
    }

    @Test
    fun `reset drops the tail`() {
        val karaoke = stage()
        run(karaoke, tone(seconds = 0.1f, hz = 60f, left = 0.5f, right = 0.5f))
        val ringing = run(karaoke, List(200) { FloatArray(2) })

        karaoke.reset()
        val afterReset = run(karaoke, List(200) { FloatArray(2) })

        assertTrue("the filter rings on before the reset", ringing.any { it[0] != 0f })
        assertTrue("nothing of the previous audio comes through after the reset", afterReset.all { it[0] == 0f && it[1] == 0f })
    }

    @Test
    fun `mono has no center to cancel and is left alone`() {
        val mono = floatArrayOf(0.5f)
        val empty = FloatArray(0)

        stage().process(mono)
        stage().process(empty)

        assertArrayEquals("a mono frame is left unchanged", floatArrayOf(0.5f), mono, 0f)
    }

    @Test
    fun `the sides come back in antiphase`() {
        // what separates this from a plain L-R karaoke; a loudness check
        // cannot see it
        val stage = stage(level = 1f, width = 0f)
        val left = mutableListOf<Float>()
        val right = mutableListOf<Float>()
        repeat(2000) { n ->
            val tone = sin(2 * Math.PI * 440 * n / rate).toFloat() * 0.5f
            val frame = floatArrayOf(tone, -tone * 0.5f) // something off center
            stage.process(frame)
            left += frame[0]
            right += frame[1]
        }

        val correlation = correlation(left, right)
        assertTrue("the channels stay opposed: $correlation", correlation < -0.5f)
    }

    private fun correlation(
        a: List<Float>,
        b: List<Float>,
    ): Float {
        val dot = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }
        val na = kotlin.math.sqrt(a.sumOf { (it * it).toDouble() })
        val nb = kotlin.math.sqrt(b.sumOf { (it * it).toDouble() })
        return if (na == 0.0 || nb == 0.0) 0f else (dot / (na * nb)).toFloat()
    }
}
