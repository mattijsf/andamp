// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The balance slider as a Media3 audio processor: per-channel gain on the PCM
 * stream, beside [EqAudioProcessor] in the same chain.
 *
 * The setting is written from any thread through a volatile field and read on
 * the audio thread. Samples pass through untouched at center, and an encoding
 * [Pcm] does not handle is bypassed.
 */
class BalanceAudioProcessor : BaseAudioProcessor() {
    /** -1 hard left, 0 center, +1 hard right. */
    @Volatile private var balance: Float = 0f

    private var channels = 0
    private var encoding = C.ENCODING_PCM_16BIT

    fun update(balance: Float) {
        this.balance = balance.coerceIn(-1f, 1f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!Pcm.speaks(inputAudioFormat.encoding)) {
            return AudioProcessor.AudioFormat.NOT_SET // bypass, like the EQ does
        }
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // The pipeline drains by running the chain on the shared EMPTY_BUFFER,
        // and replaceOutputBuffer(0) returns that same instance; putting a
        // buffer into itself throws.
        if (!inputBuffer.hasRemaining()) return
        val setting = balance
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (setting == 0f || channels < 2) {
            output.put(inputBuffer)
        } else {
            // PCM is little-endian on the wire whatever the buffer's own order
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val gains = FloatArray(channels) { BalanceGain.forChannel(it, channels, setting) }
            val width = Pcm.bytesPerSample(encoding)
            var channel = 0
            while (inputBuffer.remaining() >= width) {
                if (encoding == C.ENCODING_PCM_16BIT) {
                    // scaled as a 16-bit value, without normalizing to float first
                    val scaled = (inputBuffer.short * gains[channel]).toInt()
                    output.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                } else {
                    output.putFloat(inputBuffer.float * gains[channel])
                }
                channel = (channel + 1) % channels
            }
        }
        output.flip()
    }
}
