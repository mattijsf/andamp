// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import nl.mattix.andamp.core.playback.PcmRingBuffer
import java.nio.ByteBuffer

/**
 * An audio sink that tells the visualizer tap how far it runs ahead of the ear.
 *
 * It changes nothing about what [sink] plays. It watches the buffers go in and the position
 * come out ([AheadMeter]), and reports the difference to [ring] each time the player asks
 * for the position, which is several times a second while it plays. At a pause and at a
 * seek it tells the ring the sound has stopped moving.
 */
internal class AheadReportingAudioSink(
    sink: AudioSink,
    private val ring: PcmRingBuffer,
) : ForwardingAudioSink(sink) {
    private val meter = AheadMeter()

    override fun configure(config: AudioSink.AudioSinkConfig) {
        super.configure(config)
        val format = config.format
        val pcm = Util.isEncodingLinearPcm(format.pcmEncoding)
        meter.configure(
            format.sampleRate,
            if (pcm) Util.getPcmFrameSize(format.pcmEncoding, format.channelCount) else 0,
        )
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        meter.offered(presentationTimeUs, buffer.remaining())
        val taken = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (taken) meter.taken()
        return taken
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val heardUs = super.getCurrentPositionUs(sourceEnded)
        if (heardUs != AudioSink.CURRENT_POSITION_NOT_SET) {
            val ahead = meter.aheadSamples(heardUs)
            if (ahead >= 0) ring.reportAhead(ahead)
        }
        return heardUs
    }

    override fun pause() {
        super.pause()
        ring.holdAhead()
    }

    override fun flush() {
        super.flush()
        meter.forget()
        ring.holdAhead()
    }

    override fun reset() {
        super.reset()
        meter.forget()
        ring.holdAhead()
    }
}
