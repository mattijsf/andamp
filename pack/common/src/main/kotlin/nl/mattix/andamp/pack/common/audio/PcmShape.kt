// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import nl.mattix.andamp.core.playback.PcmProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Converts the sample encoding and the channel layout a codec produced into
 * [PcmProvider]'s: 16-bit little endian, two channels.
 *
 * It is arithmetic over arrays with nothing from Android, so it is tested on a JVM. The
 * buffers are the caller's; nothing here allocates.
 */
internal object PcmShape {
    /** `AudioFormat.ENCODING_PCM_16BIT`: what a codec answers unless it says otherwise. */
    const val ENCODING_PCM_16BIT = 2

    /** `AudioFormat.ENCODING_PCM_FLOAT`: samples as floats nominally within ±1.0. */
    const val ENCODING_PCM_FLOAT = 4

    /** `AudioFormat.ENCODING_PCM_24BIT_PACKED`: three bytes a sample, little endian, no padding. */
    const val ENCODING_PCM_24BIT_PACKED = 21

    /** `AudioFormat.ENCODING_PCM_32BIT`: four bytes a sample, the top 24 of them meaningful. */
    const val ENCODING_PCM_32BIT = 22

    /**
     * Reads [bytes] of [source] in [encoding] and writes them into [into] as 16-bit
     * samples, answering how many.
     *
     * The encoding constants are declared in this file so that it needs no Android; the
     * packed 24-bit and the 32-bit one became public in `AudioFormat` at API 31.
     *
     * The buffer is read as little endian. A `ByteBuffer` defaults to big endian, and
     * MediaCodec's PCM is in native order, which is little endian on every Android ABI.
     *
     * Anything wider than 16 bits is truncated, without dither.
     */
    fun samples(
        source: ByteBuffer,
        bytes: Int,
        encoding: Int,
        into: ShortArray,
    ): Int {
        source.order(ByteOrder.LITTLE_ENDIAN)
        return when (encoding) {
            ENCODING_PCM_FLOAT -> fromFloat(source, bytes, into)
            ENCODING_PCM_24BIT_PACKED -> fromPacked24(source, bytes, into)
            ENCODING_PCM_32BIT -> fromWide32(source, bytes, into)
            else -> fromShort(source, bytes, into)
        }
    }

    /**
     * [samples] samples in [channels] channels, written into [into] as interleaved stereo;
     * answers how many samples that came to.
     *
     * Mono is duplicated to both channels. More than two channels are folded by taking the
     * first two, the front pair. No downmix matrix is applied, because a codec's output
     * format does not reliably carry the channel mask one would need.
     */
    fun fold(
        source: ShortArray,
        samples: Int,
        channels: Int,
        into: ShortArray,
    ): Int {
        val lanes = channels.coerceAtLeast(1)
        val frames = samples / lanes
        var at = 0
        for (frame in 0 until frames) {
            val start = frame * lanes
            into[at++] = source[start]
            into[at++] = if (lanes == 1) source[start] else source[start + 1]
        }
        return at
    }

    /** [samples] shorts written into [into] as little endian bytes; answers how many bytes. */
    fun bytes(
        source: ShortArray,
        samples: Int,
        into: ByteArray,
    ): Int {
        var at = 0
        for (index in 0 until samples) {
            val sample = source[index].toInt()
            into[at++] = (sample and 0xFF).toByte()
            into[at++] = ((sample shr 8) and 0xFF).toByte()
        }
        return at
    }

    private fun fromShort(
        source: ByteBuffer,
        bytes: Int,
        into: ShortArray,
    ): Int {
        val count = minOf(bytes / Short.SIZE_BYTES, into.size)
        for (index in 0 until count) into[index] = source.short
        return count
    }

    /**
     * Floats to 16-bit, clipped at full scale. A codec may answer above ±1.0, and an
     * unclipped conversion would wrap. Full scale is ±32767, symmetric.
     */
    private fun fromFloat(
        source: ByteBuffer,
        bytes: Int,
        into: ShortArray,
    ): Int {
        val count = minOf(bytes / Float.SIZE_BYTES, into.size)
        for (index in 0 until count) {
            val clipped = source.float.coerceIn(-1f, 1f)
            into[index] = (clipped * Short.MAX_VALUE).roundToInt().toShort()
        }
        return count
    }

    /** Three little endian bytes a sample, of which the top two are kept. */
    private fun fromPacked24(
        source: ByteBuffer,
        bytes: Int,
        into: ShortArray,
    ): Int {
        val count = minOf(bytes / PACKED_24_BYTES, into.size)
        for (index in 0 until count) {
            source.get()
            val low = source.get().toInt() and 0xFF
            val high = source.get().toInt()
            into[index] = ((high shl 8) or low).toShort()
        }
        return count
    }

    /** Four bytes a sample with the sample in the top 24 bits; the top 16 are kept. */
    private fun fromWide32(
        source: ByteBuffer,
        bytes: Int,
        into: ShortArray,
    ): Int {
        val count = minOf(bytes / Int.SIZE_BYTES, into.size)
        for (index in 0 until count) into[index] = (source.int shr 16).toShort()
        return count
    }

    private const val PACKED_24_BYTES = 3
}
