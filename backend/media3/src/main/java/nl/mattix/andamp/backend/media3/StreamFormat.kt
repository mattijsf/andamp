// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import nl.mattix.andamp.core.model.Track
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Winamp's kbps and kHz readouts, filled from the stream the decoder is
 * reading and not from tags.
 *
 * A row from the device's library may carry neither number, and the decoder
 * knows both for the track that is playing.
 */
internal object StreamFormat {
    /** The mp3 mime type; only mp3 is snapped to a bitrate ladder. */
    const val MPEG_AUDIO = "audio/mpeg"

    /**
     * The stream's bitrate as a readout, or null when it is not reported.
     *
     * Media3 reports an average for the whole file, so a 192k mp3 can arrive
     * as 192.3k. An mp3 whose average is within [LADDER_TOLERANCE] of a rung
     * of the layer III ladder is shown as that rung, which is what a frame
     * header would say. A VBR file between rungs keeps its average.
     */
    fun kbps(
        bitrateBps: Int,
        mimeType: String? = null,
    ): Int? {
        val kbps = bitrateBps.takeIf { it > 0 }?.div(BITS_PER_KBIT.toFloat())?.takeIf { it >= 1f } ?: return null
        if (mimeType != MPEG_AUDIO) return kbps.roundToInt()
        val rung = MP3_LADDER.minBy { abs(it - kbps) }
        return if (abs(rung - kbps) <= rung * LADDER_TOLERANCE) rung else kbps.roundToInt()
    }

    fun khz(sampleRateHz: Int): Int? =
        sampleRateHz
            .takeIf { it > 0 }
            ?.div(HZ_PER_KHZ.toFloat())
            ?.takeIf { it >= 1f }
            ?.toInt()

    /**
     * [track] with what the stream reported. A value it did not report is
     * left as it was.
     */
    fun patch(
        track: Track,
        bitrateBps: Int,
        sampleRateHz: Int,
        mimeType: String? = null,
    ): Track =
        track.copy(
            bitrateKbps = kbps(bitrateBps, mimeType) ?: track.bitrateKbps,
            sampleRateKhz = khz(sampleRateHz) ?: track.sampleRateKhz,
        )

    /** The layer III bitrates the readout snaps to, in kbit/s. */
    private val MP3_LADDER =
        intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 192, 224, 256, 320)

    /**
     * How far from a rung, as a fraction of it, the reported average may sit
     * and still be shown as that rung.
     */
    private const val LADDER_TOLERANCE = 0.02f

    private const val BITS_PER_KBIT = 1000
    private const val HZ_PER_KHZ = 1000
}
