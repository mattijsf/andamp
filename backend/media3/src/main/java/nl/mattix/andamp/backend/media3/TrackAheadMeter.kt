// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

/**
 * How far the frames handed to an audio track are ahead of the frames that are heard.
 *
 * It is two distances added up. The first is what the track still holds: the frames handed
 * to it since it was opened or emptied, less the frames it has played, which the track
 * counts in its playback head. The second is what the device holds after the track, which
 * a timestamp of the track tells: the frame that reached the speaker and when it did. A
 * track gives no timestamp for a moment after it starts, and the last one known is used
 * until it does, because the device behind the track is the same.
 *
 * Not safe to share between threads; whoever owns one guards it.
 */
internal class TrackAheadMeter(
    private val sampleRateHz: Int,
) {
    private var handed = 0L
    private var played = 0L
    private var lastHead = 0
    private var behindHead = 0L

    /** [frames] more are on their way into the track. */
    fun handed(frames: Int) {
        handed += frames
    }

    /** The track was opened or flushed: it holds nothing, and its playback head is at 0 again. */
    fun emptied() {
        handed = 0
        played = 0
        lastHead = 0
    }

    /**
     * A timestamp of the track: frame [framePosition] reached the speaker [sinceNanos] ago,
     * while the playback head is at [head]. One that is old, or that puts the speaker ahead
     * of the head or implausibly far behind it, is left out. So is one at frame 0, which a
     * track gives before any of its frames has come out.
     */
    fun presented(
        head: Int,
        framePosition: Long,
        sinceNanos: Long,
    ) {
        if (framePosition <= 0 || sinceNanos !in 0..FRESH_NANOS) return
        // both count frames in 32 bits and start over together, so their difference holds
        // across the point where they do
        val behind = (head - framePosition.toInt()) - sinceNanos * sampleRateHz / NANOS_PER_SECOND
        if (behind in 0..sampleRateHz * MOST_BEHIND_S) behindHead = behind
    }

    /**
     * The frames between the last one handed over and the one that is heard, with the
     * track's playback head at [head]. The head is an unsigned 32-bit count that starts
     * over when it is full, so it is followed by its steps and not read as a total.
     */
    fun ahead(head: Int): Long {
        val step = head - lastHead
        if (step > 0) played += step
        lastHead = head
        // a frame that was played was handed over
        if (handed < played) handed = played
        return handed - played + behindHead
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** A playing track renews its timestamp many times a second; an older one is of a track that stands still. */
        const val FRESH_NANOS = NANOS_PER_SECOND / 2

        /** More than any output holds; a wireless speaker is some hundreds of milliseconds behind. */
        const val MOST_BEHIND_S = 2L
    }
}
