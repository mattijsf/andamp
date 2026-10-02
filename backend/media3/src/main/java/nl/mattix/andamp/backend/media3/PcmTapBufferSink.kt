// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import nl.mattix.andamp.core.playback.PcmRingBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives the audio sink's PCM stream (via [TeeAudioProcessor]), downmixes
 * to mono floats and feeds the [PcmRingBuffer] visualizers read from.
 * Runs on the playback thread. The scratch array is reused and grows only
 * when a larger buffer arrives.
 */
internal class PcmTapBufferSink(
    private val ring: PcmRingBuffer,
) : TeeAudioProcessor.AudioBufferSink {
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var scratch = FloatArray(4096)

    override fun flush(
        sampleRateHz: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        this.channelCount = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        ring.onFormatChanged(sampleRateHz)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = data.remaining() / (bytesPerSample * channelCount)
        if (frames <= 0) return
        if (scratch.size < frames) scratch = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            repeat(channelCount) {
                acc +=
                    if (encoding == C.ENCODING_PCM_FLOAT) {
                        data.float
                    } else {
                        data.short / 32768f
                    }
            }
            scratch[f] = acc / channelCount
        }
        ring.write(scratch, frames)
    }
}
