// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

import java.util.concurrent.atomic.AtomicLong

/**
 * The full observable playback state a backend emits. A UI shows [positionMs] from here and
 * keeps no playback clock of its own.
 */
data class BackendState(
    val transport: Transport = Transport.Stopped,
    val positionMs: Long = 0,
    val currentIndex: Int = 0,
    val queue: List<Track> = emptyList(),
    val volumeFraction: Float = 0.78f,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    /**
     * The bitrate of the frame being decoded, when the backend can say; Winamp's readout
     * moved frame by frame through a VBR file. Null leaves the readout on
     * [Track.bitrateKbps].
     */
    val streamBitrateKbps: Int? = null,
    /**
     * The sample rate the decoder is producing, when the backend can say. What a streaming
     * source sends depends on its quality setting and its server, so the rate is reported
     * while it plays. Null leaves the readout on [Track.sampleRateKhz].
     */
    val streamSampleRateKhz: Int? = null,
    /**
     * True from reaching for the source until its first audio, as when connecting to a
     * station. False during a mid-stream rebuffer.
     */
    val connecting: Boolean = false,
    /** What the current station said about itself on connecting; null off a stream. */
    val station: StationHeaders? = null,
    /** Something the listener has to be told, or null. See [BackendNotice]. */
    val notice: BackendNotice? = null,
    /**
     * The number of this raising of [notice], set by [raising].
     *
     * A flow of states does not emit a state equal to the last one, so the number is what
     * makes a repeated notice a new state. The player shows each number once, and does not
     * show a notice whose number is 0.
     */
    val noticeSeq: Long = 0,
) {
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)

    /**
     * This state with [notice], numbered as a new raising even when the notice equals the
     * last one.
     *
     * The count is one per process and only goes up, so two backends in one process never
     * raise a notice under the same number.
     */
    fun raising(notice: BackendNotice): BackendState = copy(notice = notice, noticeSeq = raised.incrementAndGet())

    private companion object {
        /** Every notice raised in this process so far. */
        val raised = AtomicLong()
    }
}
