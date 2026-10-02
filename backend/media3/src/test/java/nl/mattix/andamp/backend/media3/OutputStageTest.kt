// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.PcmRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * The whole chain, as [audioChain] builds it, handling peaks past full scale.
 *
 * The chain runs in float, so an equalizer boost past full scale reaches the
 * last stage. With the limiter off, the default, that stage clips it and
 * reports the clip. With it on, the gain is turned down ahead of the peak.
 */
class OutputStageTest {
    private val rate = 44_100

    private class Chain(
        val eq: EqAudioProcessor = EqAudioProcessor(),
        val dsp: DspAudioProcessor = DspAudioProcessor(),
        val ring: PcmRingBuffer = PcmRingBuffer(),
    ) {
        val pipeline =
            AudioProcessingPipeline(ImmutableList.copyOf(audioChain(eq, BalanceAudioProcessor(), dsp, ring))).also {
                it.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
                it.flush(AudioProcessor.StreamMetadata.DEFAULT)
            }

        /** Runs [left] (the same in both channels) through, in buffers of 512 frames; returns the left channel out. */
        fun run(left: ShortArray): ShortArray {
            val out = ArrayList<Short>(left.size)
            var from = 0
            while (from < left.size) {
                val frames = minOf(512, left.size - from)
                val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in from until from + frames) {
                    input.putShort(left[i])
                    input.putShort(left[i])
                }
                input.flip()
                while (input.hasRemaining()) {
                    pipeline.queueInput(input)
                    drain(out)
                }
                from += frames
            }
            drain(out)
            return out.toShortArray()
        }

        private fun drain(into: MutableList<Short>) {
            val output = pipeline.output.order(ByteOrder.LITTLE_ENDIAN)
            while (output.remaining() >= 4) {
                into += output.short
                output.short
            }
        }
    }

    private fun sine(
        hz: Double,
        amplitude: Double,
        frames: Int,
    ) = ShortArray(frames) { (sin(2 * PI * hz * it / rate) * amplitude * 32767).toInt().toShort() }

    private fun boosted(db: Float) = EqSettings(enabled = true, preampDb = db, bandsDb = List(10) { 0f })

    /** How strong [hz] is in the last 16384 samples of [x], as a fraction of full scale. */
    private fun level(
        x: ShortArray,
        hz: Double,
    ): Double {
        val n = 16_384
        val from = x.size - n
        var re = 0.0
        var im = 0.0
        var weight = 0.0
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            val v = x[from + i] / 32768.0
            re += v * w * cos(2 * PI * hz * i / rate)
            im += v * w * sin(2 * PI * hz * i / rate)
            weight += w
        }
        return 2 * hypot(re, im) / weight
    }

    @Test
    fun `off, a track that fits comes out exactly as it went in, with no delay`() {
        val chain = Chain()
        val input = sine(440.0, 0.99, rate / 2)

        val out = chain.run(input)

        assertTrue("the chain leaves every sample unchanged", input.contentEquals(out))
        assertEquals("no clip is reported", 0L, chain.ring.peaks.clippedAtNanos)
    }

    @Test
    fun `off, a boost past full scale is clipped and the clip is reported`() {
        val chain = Chain()
        chain.eq.update(boosted(6f))

        val out = chain.run(sine(440.0, 0.9, rate / 2))

        assertTrue("the output is clipped at full scale", out.maxOf { abs(it.toInt()) } >= 32767)
        assertTrue("the clip is reported", chain.ring.peaks.clippedAtNanos > 0L)
        assertEquals("no gain reduction is reported", 0f, chain.ring.peaks.holdingDb, 0f)
    }

    /**
     * The chain carries a boosted sample in float to the last stage, where the
     * limiter turns it down.
     */
    @Test
    fun `on, a boost past full scale is held under -1 dBFS without distortion`() {
        // set before the stream starts, as a remembered rack is
        val chain = Chain(dsp = DspAudioProcessor().apply { update(RackSettings(limiter = true)) })
        chain.eq.update(boosted(12f))

        val out = chain.run(sine(440.0, 0.9, rate * 2))

        val peak = out.maxOf { abs(it.toInt()) } / 32768.0
        assertTrue("the peak stays at or below 0.9: $peak", peak <= 0.9)
        assertTrue("no sample reaches full scale", chain.ring.peaks.clippedAtNanos == 0L)
        // 0.9 x 4 (12 dB) held to 0.891: about -12 dB of gain reduction
        assertEquals(
            -12.1,
            chain.ring.peaks.holdingDb
                .toDouble(),
            0.5,
        )
        val fundamental = level(out, 440.0)
        listOf(880.0, 1320.0, 2200.0).forEach { hz ->
            val distortion = 20 * log10(level(out, hz) / fundamental)
            assertTrue("distortion at $hz Hz is below -60 dB: $distortion dB", distortion < -60)
        }
    }

    @Test
    fun `on, a track that fits comes out unchanged 5 ms later`() {
        val chain = Chain(dsp = DspAudioProcessor().apply { update(RackSettings(limiter = true)) })
        val input = sine(440.0, 0.85, rate / 2)

        val out = chain.run(input)

        val ahead = (rate * 5 / 1000)
        for (i in ahead until input.size) {
            assertEquals("sample $i", input[i - ahead], out[i])
        }
        assertEquals(0f, chain.ring.peaks.holdingDb, 0f)
    }

    /** The limiter delays the audio by 5 ms; switching it mid-song must not be heard as a click. */
    @Test
    fun `switching it mid-song fades rather than jumps`() {
        val chain = Chain()
        val song = sine(100.0, 0.5, rate)
        val first = chain.run(song.copyOfRange(0, rate / 2))
        chain.dsp.update(RackSettings(limiter = true))
        val second = chain.run(song.copyOfRange(rate / 2, rate))
        val out = first + second

        // a 100 Hz sine at half scale moves at most about 0.007 of full scale a sample
        var worst = 0
        for (i in 1 until out.size) worst = maxOf(worst, abs(out[i] - out[i - 1]))
        assertTrue("no sample-to-sample step reaches 0.02 of full scale: ${worst / 32768.0}", worst / 32768.0 < 0.02)
    }
}
