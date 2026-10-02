// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import nl.mattix.andamp.core.playback.PcmRingBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * The last stage: float in, 16-bit out, and where a peak past full scale is
 * handled.
 *
 * With the limiter off, the default, it only converts: a sample that fits is
 * unchanged, with no delay, and one that does not is clipped. The time of the
 * last clip is reported to [ring], so the UI can show that it happened.
 *
 * With the limiter on, it is a peak limiter that looks [AHEAD_MS] ahead. The
 * audio is delayed by that much. When a peak enters, the gain is eased down
 * over the look-ahead so it is in place when the peak comes out, and it
 * recovers over [RELEASE_MS]. The limiter changes the gain and does not
 * reshape the wave, and a track that never passes [CEILING] is only delayed.
 *
 * Switching between the two changes the delay, so the output fades to silence
 * over [DUCK_MS], switches there, and fades back.
 *
 * @param wanted whether the limiter is on, read once per buffer; the rack's setting
 * @param ring where the peak readings are reported; see [PeakReading]
 */
class OutputAudioProcessor(
    private val wanted: () -> Boolean,
    private val ring: PcmRingBuffer? = null,
) : BaseAudioProcessor() {
    private var encoding = C.ENCODING_PCM_FLOAT
    private var channels = 0

    /** Whether the limiter is running, which trails [wanted] by a fade. */
    private var limiting = false

    private var ahead = 0
    private var line = FloatArray(0)
    private var at = 0
    private var held = 0f
    private var down = 0f
    private var attack = 0f
    private var release = 0f

    private var duck = 1f
    private var duckStep = 0f
    private var ducking = 0

    private var clippedAt = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!Pcm.speaks(inputAudioFormat.encoding)) return AudioProcessor.AudioFormat.NOT_SET
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        ahead = max(1, (rate * AHEAD_MS / MS_PER_SEC).toInt())
        line = FloatArray(ahead * channels)
        // an envelope gets within a quarter of a percent of its target in six
        // time constants, which are fitted into the look-ahead
        attack = exp(-1.0 / (ahead / ATTACK_CONSTANTS)).toFloat()
        release = exp(-1.0 / (rate * RELEASE_MS / MS_PER_SEC)).toFloat()
        duckStep = 1f / max(1f, rate * DUCK_MS / MS_PER_SEC)
        limiting = wanted()
        clear()
        return AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // an empty input is the pipeline draining; see BalanceAudioProcessor
        if (!inputBuffer.hasRemaining()) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val width = Pcm.bytesPerSample(encoding)
        val frames = inputBuffer.remaining() / (width * channels)
        val output = replaceOutputBuffer(frames * 2 * channels)
        if (wanted() != limiting && ducking == 0) ducking = DOWN
        var lowest = 1f
        repeat(frames) {
            if (limiting) {
                lowest = minOf(lowest, limit(inputBuffer, output))
            } else {
                pass(inputBuffer, output)
            }
            if (ducking != 0) duckOn()
        }
        // a trailing partial frame is dropped
        inputBuffer.position(inputBuffer.limit())
        output.flip()
        ring?.reportPeaks(if (limiting && lowest < 1f) DB * log10(lowest) else 0f, clippedAt)
    }

    /** One frame converted as it came; a sample past full scale is recorded as a clip. */
    private fun pass(
        input: ByteBuffer,
        output: ByteBuffer,
    ) {
        repeat(channels) {
            val sample = Pcm.read(input, encoding) * duck
            if (abs(sample) > 1f) clippedAt = System.nanoTime()
            output.putShort(Pcm.toShort(sample))
        }
    }

    /** One frame through the limiter; returns the gain applied. */
    private fun limit(
        input: ByteBuffer,
        output: ByteBuffer,
    ): Float {
        var peak = 0f
        val frameAt = at * channels
        for (c in 0 until channels) {
            val sample = Pcm.read(input, encoding)
            peak = max(peak, abs(sample))
            // swap what came in for what is due out, in the same slot
            val due = line[frameAt + c]
            line[frameAt + c] = sample
            scratch[c] = due
        }
        at = (at + 1) % ahead
        val want = if (peak > CEILING) 1f - CEILING / peak else 0f
        // rises at once and falls slowly, so a peak one sample wide counts in full
        held = if (want >= held) want else want + (held - want) * release
        // eased in over the look-ahead; follows held straight down when it falls
        down = if (held > down) held + (down - held) * attack else held
        val gain = 1f - down
        for (c in 0 until channels) {
            val sample = scratch[c] * gain * duck
            if (abs(sample) > 1f) clippedAt = System.nanoTime()
            output.putShort(Pcm.toShort(sample))
        }
        return gain
    }

    private var scratch = FloatArray(MAX_CHANNELS)

    /** Moves the fade along by a frame, switching at the bottom. */
    private fun duckOn() {
        duck += ducking * duckStep
        if (ducking == DOWN && duck <= 0f) {
            duck = 0f
            limiting = wanted()
            clear()
            ducking = UP
        } else if (ducking == UP && duck >= 1f) {
            duck = 1f
            ducking = 0
        }
    }

    private fun clear() {
        line.fill(0f)
        at = 0
        held = 0f
        down = 0f
        if (scratch.size < channels) scratch = FloatArray(channels)
    }

    override fun onFlush() {
        // a seek does not carry the old audio's last milliseconds into the new
        clear()
        duck = 1f
        ducking = 0
        limiting = wanted()
    }

    private companion object {
        /** -1 dBFS: the limiter keeps every peak under this. */
        const val CEILING = 0.891f
        const val AHEAD_MS = 5f
        const val RELEASE_MS = 250f
        const val DUCK_MS = 10f
        const val ATTACK_CONSTANTS = 6.0
        const val MS_PER_SEC = 1000f
        const val DB = 20f
        const val MAX_CHANNELS = 8
        const val DOWN = -1
        const val UP = 1
    }
}
