// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import java.nio.ByteBuffer

/**
 * Reads and writes one sample in either of the two encodings the chain
 * handles: 16-bit, which a decoder hands over and the audio device is given,
 * and float, which runs between them.
 *
 * Float runs between the stages because 16-bit cannot hold a sample louder
 * than full scale: an equalizer boost would be clipped before a later stage
 * could turn it down.
 *
 * Samples are little-endian; a caller sets its input buffer's order before
 * reading.
 */
internal object Pcm {
    const val FULL_SCALE = 32768f

    fun speaks(encoding: Int) = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT

    fun bytesPerSample(encoding: Int) = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2

    /** The next sample: -1..1 from 16-bit, and possibly beyond that from float. */
    fun read(
        buffer: ByteBuffer,
        encoding: Int,
    ): Float = if (encoding == C.ENCODING_PCM_FLOAT) buffer.float else buffer.short / FULL_SCALE

    /** Writes [sample]; in 16-bit, a value past full scale is clipped. */
    fun write(
        buffer: ByteBuffer,
        encoding: Int,
        sample: Float,
    ) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            buffer.putFloat(sample)
        } else {
            buffer.putShort(toShort(sample))
        }
    }

    fun toShort(sample: Float): Short =
        (sample * FULL_SCALE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
