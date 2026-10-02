// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.pack.common.audio.DecoderTurn
import nl.mattix.andamp.pack.common.audio.Ending
import nl.mattix.andamp.pack.common.audio.StreamAudio

/**
 * One track's audio, behaving as the decoder does.
 *
 * It hands over a fixed number of bytes per tick of the test's clock, because the backend's
 * position is counted from those bytes. It stops the ways a real one stops:
 * [Ending.Finished], [Ending.Broke] or [Ending.Dropped].
 *
 * The bytes accumulate on the scope it is given, from [start] on, so the contract suite's
 * virtual clock drives it. The coroutine that grants them is launched at construction, so
 * the grant for an instant is scheduled ahead of the read that takes it.
 *
 * It has two phases, as the real decoder has. [start] is the network and costs no codec.
 * Bytes flow once the fake has taken the backend's [DecoderTurn], which it tries for on
 * each tick. It gives the turn back a tick before the one that grants the last of the
 * track, as the real decoder releases its codec while its tail is still in the pipe. A
 * track that is [broken] never takes the turn. Each phase is written to [log].
 *
 * A duration of 0 is a stream that never runs out.
 */
internal class FakeStreamAudio(
    scope: CoroutineScope,
    /** How long the track is; nought for a stream that does not end. */
    durationMs: Long,
    /** The backend's one decoder at a time, shared by every fake it opens. */
    private val turn: DecoderTurn,
    startMs: Long = 0,
    /** A track that will not play at all, and what the decoder would say about it. */
    private val broken: String? = null,
    /**
     * A server that could not be reached when this was opened: the decoder says
     * [Ending.Dropped] at the first read, having handed nothing over.
     */
    private val unreachable: Boolean = false,
    private val tickMs: Long = TICK_MS,
    /** Every call, in order, so a test can assert what the decoder was told. */
    val told: MutableList<String> = mutableListOf(),
    /** What this track is called in [log]. */
    val label: String = "audio",
    /**
     * Shared by the fakes of one test: `open <label>` when the source is opened,
     * `codec <label>` when it takes the turn and `free <label>` when it gives it.
     */
    private val log: MutableList<String> = mutableListOf(),
    /**
     * False for a transcode the server produces as it sends it: a [seekTo] is then recorded
     * and does nothing, as `MediaExtractor` does over a 200 with no byte ranges.
     */
    override val seekable: Boolean = true,
) : StreamAudio {
    private val total = if (durationMs <= 0) Long.MAX_VALUE else bytes(durationMs)

    /** What has been handed over, which is where in the track this is. */
    private var handed = bytes(startMs)

    /** What may be handed over before the next tick; see the class KDoc. */
    private var budget = 0L

    private var started = false

    /** Whether this one holds the [turn], which is having a codec. */
    private var decoding = false

    /** Decoded to its last frame: what is left is the tail, granted with no codec. */
    private var drained = false

    /**
     * A server that has gone quiet without closing the connection: nothing more is handed
     * over, and there is no [Ending].
     */
    fun stall() {
        started = false
        budget = 0
    }

    /** The server answering again after a [stall]: the ticks grant bytes from the next one on. */
    fun unstall() {
        started = true
    }

    /**
     * The connection is lost under the decoder, mid-track: nothing more is handed over,
     * what it had decoded ahead is dropped, and the next read answers -1 with [how] as the
     * reason.
     *
     * [how] is [Ending.Dropped] for a failure the decoder could name, and
     * [Ending.Finished] for what the platform's extractor usually does: report the lost
     * connection as the end of the file.
     */
    fun lose(how: Ending = Ending.Dropped("connection reset by peer")) {
        lost = how
        budget = 0
        letGo()
    }

    /** Where this track has got to, as the bytes that have been read from it say. */
    val handedMs: Long get() = handed * 10 / 1_764

    /** Set by [lose]: how the next read ends. */
    private var lost: Ending? = null

    override var ending: Ending? = null
        private set

    init {
        scope.launch {
            while (true) {
                delay(tickMs)
                tick()
            }
        }
    }

    override fun start(): StreamAudio {
        told += "start"
        if (broken == null && !unreachable) log += "open $label"
        started = true
        return this
    }

    override fun read(into: ByteArray): Int {
        val over =
            broken?.let(Ending::Broke)
                ?: Ending.Dropped("connection refused").takeIf { unreachable }
                ?: lost
                ?: Ending.Finished.takeIf { handed >= total }
        if (over != null) return stopped(over)
        val give = minOf(into.size.toLong(), budget, total - handed)
        if (give <= 0) return NOTHING_YET
        budget -= give
        handed += give
        return give.toInt()
    }

    /** What a discard is here: the decoded audio waiting for the player goes. */
    override fun discard() {
        told += "discard"
        budget = 0
    }

    /**
     * A decoder told where to go.
     *
     * Once [drained] it behaves as the real one does: its thread has gone, the seek
     * discards the tail and nothing decodes the new place, so the reads run out.
     */
    override fun seekTo(positionMs: Long) {
        told += "seekTo($positionMs)"
        if (!seekable) return
        budget = 0
        handed = if (drained) total else bytes(positionMs)
    }

    override fun release() {
        told += "release"
        started = false
        letGo()
    }

    /**
     * One tick of decoding: the turn is taken if it can be, and a tick of bytes is granted
     * while it is held or while the tail is still to be read. The turn is given when the
     * next tick would grant the last of the track.
     */
    private fun tick() {
        val ended = broken != null || unreachable || lost != null
        if (!started || ended) return
        if (!decoding && !drained && turn.tryTake(this)) {
            decoding = true
            log += "codec $label"
        }
        if (!decoding && !drained) return
        budget = bytes(tickMs)
        if (decoding && handed + budget + bytes(tickMs) >= total) {
            drained = true
            // the real decoder sets its ending when its thread stops, with the tail still
            // to be read
            ending = Ending.Finished
            letGo()
        }
    }

    private fun letGo() {
        if (!decoding) return
        decoding = false
        turn.give(this)
        log += "free $label"
    }

    private fun stopped(why: Ending): Int {
        ending = why
        return NO_MORE
    }

    private companion object {
        /** What [nl.mattix.andamp.core.playback.PcmProvider.read] says in each case. */
        const val NO_MORE = -1
        const val NOTHING_YET = 0

        const val TICK_MS = 100L

        /**
         * A stretch of time as bytes of 44.1 kHz stereo 16-bit. Computed as a ratio,
         * because 176.4 bytes a millisecond is not a whole number.
         */
        fun bytes(ms: Long): Long = ms * 1_764 / 10
    }
}
