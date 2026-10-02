// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import nl.mattix.andamp.core.playback.PcmProvider

/**
 * One track's audio, as the stream backend knows it.
 *
 * An interface, so the backend's queue and transport logic can be tested on a JVM with a
 * fake in place of `MediaExtractor` and `MediaCodec`.
 *
 * It is a [PcmProvider]: the backend hands it to its
 * [nl.mattix.andamp.core.playback.AudioOut] unchanged.
 */
internal interface StreamAudio : PcmProvider {
    /** Opens the source and starts decoding. Idempotent, and returns this. */
    fun start(): StreamAudio

    /** Start decoding again from [positionMs], dropping what was decoded for where the music was. */
    fun seekTo(positionMs: Long)

    /**
     * Whether [seekTo] can move within what is open.
     *
     * False for a stream the server produces as it sends it (a transcode), which arrives
     * as a 200 with no byte ranges, so `MediaExtractor` has nothing to seek in. The backend
     * opens such a track again at the new place, with the offset in the request.
     */
    val seekable: Boolean get() = true

    /** Stops decoding and wakes whoever is waiting in a read. */
    fun release()

    /**
     * Why the decoding stopped, or null while it has not.
     *
     * It is set when the decoder stops, which is before the last of its audio has been
     * read. So a caller that reads -1 finds the reason here, and a caller that finds it set
     * while reads still succeed knows the decoder has gone: [seekTo] no longer works, and
     * moving within this track means opening it again.
     */
    val ending: Ending?
}

/**
 * How a stretch of decoding stopped. The backend advances after [Finished], skips a track
 * that [Broke], and opens one that was [Dropped] again at the place it stopped.
 */
internal sealed interface Ending {
    /** The track was decoded to its last frame: the next one should play. */
    data object Finished : Ending

    /**
     * The track will not play, and asking again would get the same answer: gone from the
     * server, not allowed, or not audio this phone can read. Already logged; [why] is for
     * the log.
     */
    data class Broke(
        val why: String,
    ) : Ending

    /**
     * The connection to the server was lost, not the track: the network failed, or the
     * server answered with a trouble that passes. Worth opening again at the place the
     * music stopped. Already logged; [why] is for the log.
     */
    data class Dropped(
        val why: String,
    ) : Ending
}
