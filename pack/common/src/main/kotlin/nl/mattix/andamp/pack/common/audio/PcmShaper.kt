// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import nl.mattix.andamp.core.playback.PcmProvider
import java.nio.ByteBuffer

/**
 * One codec buffer in, one buffer in [PcmProvider]'s format out.
 *
 * It applies [PcmShape] and [PcmRate] in order: widen what the codec answered into 16-bit
 * samples, fold those to two channels, then resample. Folding first means the resampler
 * always works on stereo.
 *
 * The intermediate arrays are owned here and reused. A codec hands over buffers of the
 * same size for the length of a track, so each array grows on the first buffer only.
 *
 * It uses nothing from Android: [reshape] takes three integers, not a `MediaFormat`.
 */
internal class PcmShaper {
    /** What [shape] wrote into, valid up to the count it answered. Reallocated only when it has to grow. */
    var shaped: ByteArray = ByteArray(0)
        private set

    private var widened = ShortArray(0)
    private var stereo = ShortArray(0)
    private var paced = ShortArray(0)

    private var channels = PcmProvider.CHANNELS
    private var encoding = PcmShape.ENCODING_PCM_16BIT
    private var sourceRate = PcmProvider.SAMPLE_RATE_HZ
    private var rate: PcmRate? = null

    /**
     * The codec has said what it is producing.
     *
     * Called for the format the extractor read from the track and again for the one the
     * codec announces before its first output buffer; the second describes the bytes. At
     * 44.1 kHz no resampler is created.
     */
    fun reshape(
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
    ) {
        val source = sampleRate.takeIf { it > 0 } ?: PcmProvider.SAMPLE_RATE_HZ
        channels = channelCount.coerceAtLeast(1)
        encoding = pcmEncoding
        sourceRate = source
        rate = if (source == PcmProvider.SAMPLE_RATE_HZ) null else PcmRate(source)
    }

    /**
     * How many of a codec buffer's [bytes], stamped [startUs], come before [untilUs], in
     * whole frames of the codec's own format (its sample rate, channels and sample width
     * as last given to [reshape]), and never more than the buffer holds.
     */
    fun bytesBefore(
        startUs: Long,
        untilUs: Long,
        bytes: Int,
    ): Int {
        if (untilUs <= startUs || bytes <= 0) return 0
        val frame = channels * width()
        val frames = (untilUs - startUs) * sourceRate / MICROS_PER_SECOND
        return minOf(frames * frame, (bytes - bytes % frame).toLong()).toInt()
    }

    /** Bytes a sample, in the codec's own [encoding]. */
    private fun width(): Int =
        when (encoding) {
            PcmShape.ENCODING_PCM_FLOAT, PcmShape.ENCODING_PCM_32BIT -> Int.SIZE_BYTES
            PcmShape.ENCODING_PCM_24BIT_PACKED -> PACKED_24_BYTES
            else -> Short.SIZE_BYTES
        }

    /** Resets the resampler's history; called after a seek. */
    fun reset() = rate?.reset() ?: Unit

    /**
     * Reads [bytes] from [source] and answers how many bytes of 44.1 kHz stereo
     * 16-bit little endian that became, in [shaped].
     */
    fun shape(
        source: ByteBuffer,
        bytes: Int,
    ): Int {
        if (bytes <= 0) return 0
        widened = roomFor(widened, bytes / Short.SIZE_BYTES)
        val samples = PcmShape.samples(source, bytes, encoding, widened)
        stereo = roomFor(stereo, samples * PcmProvider.CHANNELS)
        val folded = PcmShape.fold(widened, samples, channels, stereo)
        val resampler = rate
        var ready = stereo
        var count = folded
        if (resampler != null) {
            paced = roomFor(paced, resampler.room(folded))
            count = resampler.convert(stereo, folded, paced)
            ready = paced
        }
        if (shaped.size < count * Short.SIZE_BYTES) shaped = ByteArray(count * Short.SIZE_BYTES)
        return PcmShape.bytes(ready, count, shaped)
    }

    /**
     * [array] if it already holds [need], otherwise a new one of that size. The need is
     * the same for every buffer of a track, so there is no growth factor.
     */
    private fun roomFor(
        array: ShortArray,
        need: Int,
    ): ShortArray = if (array.size >= need) array else ShortArray(need)

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val PACKED_24_BYTES = 3
    }
}
