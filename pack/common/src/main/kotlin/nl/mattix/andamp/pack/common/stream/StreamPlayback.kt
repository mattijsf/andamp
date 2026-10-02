// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.pack.common.audio.DecoderTurn
import nl.mattix.andamp.pack.common.audio.StreamPcm

/**
 * Where one track's audio is: a URL to open, and what to send with the request.
 *
 * This is all a server pack has to say about playing a row; [StreamPlayback] does the rest.
 *
 * Make it when the row is about to play and do not keep it: the URL or the headers may
 * carry the listener's credential.
 *
 * @property url an http or https address whose response body is one music file, in any
 *   container and codec the phone's `MediaExtractor` reads
 * @property headers sent with the request for [url], through
 *   `MediaExtractor.setDataSource(url, headers)`: the place for a credential a server
 *   takes in a header; empty when there is none
 * @property seekable whether the server answers [url] with something that can be sought
 *   in: true for a file it serves as it is, false for a stream it produces as it sends it
 *   (a transcode, which comes back as a 200 with no byte ranges). When false, the audio at
 *   [url] must already begin at the position `locate` was asked for, because the pack put
 *   that offset in the request. A seek then opens the row again at the new place
 */
data class StreamRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val seekable: Boolean = true,
) {
    /**
     * Where the decoder has to seek to before its first sample, for a row opened at
     * [positionMs]: the position for a stream that can be sought in, and 0 for one that
     * cannot, which already starts there.
     */
    internal fun decoderStartMs(positionMs: Long): Long = if (seekable) positionMs else 0L

    /** Leaves out the query string and the header values, which may hold a credential. */
    override fun toString(): String = "StreamRequest(${url.substringBefore('?')}, ${headers.size} headers, seekable=$seekable)"
}

/**
 * The entry point for a pack whose music is files on a server it reaches over HTTP.
 *
 * [backend] builds a [PlaybackBackend] with Winamp's queue and transport rules. It decodes
 * each row with the platform's `MediaExtractor` and `MediaCodec` and hands the samples to
 * an [AudioOut]; in a pack that is a `PackAudioOut`, so the player renders them with its
 * equalizer, effects and visualizer. It plays one track after another in one continuous
 * stream, opens the next track's connection a few seconds before the current one ends, and
 * holds one codec at a time. A song whose connection drops is picked up where it was
 * heard, on `StreamReconnect`'s schedule, and is not skipped.
 */
object StreamPlayback {
    /**
     * What a backend from [backend] can do once it has an [AudioOut], available without
     * building one, for the descriptor a pack hands the player: seeking, an editable
     * queue, and the equalizer, balance, effects and volume that the player's chain
     * applies.
     */
    val RENDERING: Capabilities get() = StreamBackend.RENDERING

    /**
     * A backend over [tracks], starting at [startIndex], that finds each row's audio by
     * asking [locate].
     *
     * [locate] is called each time a row is about to be opened: when it is played, when a
     * stream that cannot be sought is sought, when a dropped song is picked up, and a few
     * seconds before the track ahead of it ends. So an account that changed while a queue
     * sits in the service is used from the next open on. It is given the position in the
     * row where the music should start. A pack whose server transcodes puts that into the
     * request as an offset and sets [StreamRequest.seekable] to false; one that serves
     * files as they are can ignore it, and the decoder seeks there itself. It answers null
     * for a row it has no audio for (no server set up, or a row that is not this pack's).
     * The backend skips such a row, and raises a notice when the whole queue is like it.
     *
     * [out] is where the samples go. Null drives the transport and the state with nothing
     * rendered, and turns off the capabilities that need an output.
     *
     * [network] tells the backend when there is a network to pick a dropped song up over.
     * In a pack, pass a `SystemNetworkWatch` (from `:core:network`) made with the
     * service's context. With `NetworkWatch.Assumed` it still plays, and retries on the
     * clock alone.
     */
    fun backend(
        tracks: List<Track>,
        startIndex: Int,
        scope: CoroutineScope,
        out: AudioOut?,
        network: NetworkWatch,
        locate: (track: Track, positionMs: Long) -> StreamRequest?,
    ): PlaybackBackend {
        val turn = DecoderTurn()
        return StreamBackend(
            tracks = tracks,
            scope = scope,
            open = { track, positionMs ->
                locate(track, positionMs)?.let {
                    StreamPcm(it.url, turn, it.headers, startMs = it.decoderStartMs(positionMs), seekable = it.seekable)
                }
            },
            out = out,
            startIndex = startIndex,
            network = network,
            // elapsedRealtime counts the time the phone was asleep, so a wait that Doze
            // stretched is measured at its real length
            clock = SystemClock::elapsedRealtime,
        )
    }
}
