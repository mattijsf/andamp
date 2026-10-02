// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The first stage: 16-bit in, float out, so the stages after it have headroom
 * above full scale (see [Pcm]).
 *
 * The conversion is exact: a 16-bit value divided by full scale is
 * representable as a float. Any other encoding is bypassed.
 */
class FloatInAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // an empty input is the pipeline draining; see BalanceAudioProcessor
        if (!inputBuffer.hasRemaining()) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val samples = inputBuffer.remaining() / 2
        val output = replaceOutputBuffer(samples * 4)
        repeat(samples) { output.putFloat(inputBuffer.short / Pcm.FULL_SCALE) }
        // an odd trailing byte is not a sample and is dropped
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }
}
