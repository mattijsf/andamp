// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import nl.mattix.andamp.core.model.EqSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * Winamp's 10-band graphic EQ as a Media3 audio processor: one peaking
 * biquad per band per channel plus preamp gain, applied to the PCM stream as
 * float or 16-bit (see [Pcm]). Settings arrive from any thread through a
 * volatile snapshot and are compiled to filter coefficients on the audio
 * thread. A disabled EQ passes samples through untouched, and an encoding
 * [Pcm] does not handle bypasses the processor.
 */
class EqAudioProcessor : BaseAudioProcessor() {
    @Volatile private var pending: EqSettings = EqSettings.FLAT
    private var applied: EqSettings? = null

    private var filters: Array<Biquad> = emptyArray()

    /**
     * True when every band and the preamp are within [FLAT_TOLERANCE_DB] of
     * 0 dB, so the filters are skipped.
     */
    private var transparent = false
    private var preampGain = 1f
    private var channels = 0
    private var sampleRate = 0
    private var encoding = C.ENCODING_PCM_16BIT

    fun update(settings: EqSettings) {
        pending = settings
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!Pcm.speaks(inputAudioFormat.encoding)) {
            return AudioProcessor.AudioFormat.NOT_SET // bypass: the EQ is off for other encodings
        }
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        applied = null // force coefficient rebuild for the new format
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // an empty input is the pipeline draining; see BalanceAudioProcessor
        if (!inputBuffer.hasRemaining()) return
        val settings = pending
        if (settings != applied) compile(settings)

        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining)
        if (!settings.enabled || transparent || filters.isEmpty()) {
            output.put(inputBuffer)
        } else {
            // PCM is little-endian whatever the buffer's own order
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val width = Pcm.bytesPerSample(encoding)
            var channel = 0
            while (inputBuffer.remaining() >= width) {
                var sample = Pcm.read(inputBuffer, encoding) * preampGain
                for (filter in filters) {
                    sample = filter.process(sample, channel)
                }
                Pcm.write(output, encoding, sample)
                channel = (channel + 1) % channels
            }
        }
        output.flip()
    }

    override fun onFlush() {
        for (filter in filters) filter.reset()
    }

    private fun compile(settings: EqSettings) {
        applied = settings
        preampGain = 10f.pow(settings.preampDb / 20f)
        val wasTransparent = transparent
        transparent = settings.isFlat()
        // the biquads were not fed while bypassed, so their state is stale
        if (wasTransparent && !transparent) onFlush()
        if (filters.size != EqSettings.BAND_FREQUENCIES_HZ.size) {
            filters = Array(EqSettings.BAND_FREQUENCIES_HZ.size) { Biquad(channels) }
        }
        for ((i, frequency) in EqSettings.BAND_FREQUENCIES_HZ.withIndex()) {
            if (frequency < sampleRate / 2f * NYQUIST_MARGIN) {
                filters[i].setPeaking(frequency, sampleRate, settings.bandsDb[i], BAND_Q)
            } else {
                // at or above Nyquist (12 kHz and up on a 22 kHz source) peaking
                // coefficients are unstable, so the band is an identity
                filters[i].setIdentity()
            }
        }
    }

    /** True when the preamp and every band are within [FLAT_TOLERANCE_DB] of 0 dB. */
    private fun EqSettings.isFlat() = abs(preampDb) < FLAT_TOLERANCE_DB && bandsDb.all { abs(it) < FLAT_TOLERANCE_DB }

    private companion object {
        const val FLAT_TOLERANCE_DB = 0.05f

        // moderately wide bells
        const val BAND_Q = 1.4f
        const val NYQUIST_MARGIN = 0.95f
    }
}
