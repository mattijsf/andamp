// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

/**
 * How far the audio handed to the sink is ahead of the audio that is heard.
 *
 * The sink is offered buffers stamped with their place in the stream, and reports the
 * place in the stream that is coming out of the speaker. The difference is what the sink
 * and the device still hold, which is how far the visualizer tap, the last stage before
 * the sink's track, runs ahead of the ear. It is some hundreds of milliseconds and differs
 * by phone and by output.
 *
 * Not safe to share between threads; everything here belongs to the playback thread.
 */
internal class AheadMeter {
    private var sampleRateHz = 0
    private var bytesPerFrame = 0
    private var offeredEndUs = UNSET
    private var takenUntilUs = UNSET

    /** The stream's format; [bytesPerFrame] 0 for a stream that is not PCM, which is not measured. */
    fun configure(
        sampleRateHz: Int,
        bytesPerFrame: Int,
    ) {
        this.sampleRateHz = sampleRateHz
        this.bytesPerFrame = bytesPerFrame
        forget()
    }

    /**
     * A buffer starting at [presentationTimeUs] is offered with [bytes] in it. The sink is
     * offered the same buffer again, with less left in it, until it has taken all of it, so
     * only the first offer says where the buffer ends.
     */
    fun offered(
        presentationTimeUs: Long,
        bytes: Int,
    ) {
        if (offeredEndUs != UNSET || sampleRateHz <= 0 || bytesPerFrame <= 0) return
        offeredEndUs = presentationTimeUs + (bytes / bytesPerFrame) * MICROS_PER_SECOND / sampleRateHz
    }

    /** The sink took all of the buffer last offered. */
    fun taken() {
        if (offeredEndUs == UNSET) return
        takenUntilUs = offeredEndUs
        offeredEndUs = UNSET
    }

    /** A seek or a new stream: what was handed over before no longer counts. */
    fun forget() {
        offeredEndUs = UNSET
        takenUntilUs = UNSET
    }

    /**
     * The samples between what the sink has taken and [heardUs], the place in the stream
     * that is being heard; -1 while nothing has been taken since the last [forget].
     */
    fun aheadSamples(heardUs: Long): Long {
        if (takenUntilUs == UNSET) return -1
        return ((takenUntilUs - heardUs).coerceAtLeast(0) * sampleRateHz) / MICROS_PER_SECOND
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
