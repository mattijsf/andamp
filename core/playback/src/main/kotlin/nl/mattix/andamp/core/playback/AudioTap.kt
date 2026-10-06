// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

/**
 * Pull-based access to the audio currently being played, for visualizers.
 *
 * The backend writes mono PCM into a ring buffer on its audio thread;
 * visualizers pull the latest window once per rendered frame and do their own
 * analysis (FFT bands for the classic spectrum, raw samples for an
 * oscilloscope or a Milkdrop-style renderer). Pull keeps the audio thread
 * free of backpressure and lets any number of visualizers share one tap.
 */
interface AudioTap {
    /** Source sample rate in Hz; 0 until audio has started flowing. */
    val sampleRateHz: Int

    /**
     * Total mono samples ever written. Writes happen at decode time and players buffer
     * ahead, so this head runs in bursts several hundred ms ahead of what is audible. A
     * reader that wants smooth motion advances its own clock and trails this head.
     */
    val writtenSamples: Long

    /**
     * Copies the window of `out.size` mono samples (-1..1) ending at stream position
     * [endSample], oldest first. Returns false when that window is no longer, or not yet,
     * in the buffer. A reader may observe a torn window across the write head, which is
     * acceptable for visualization.
     */
    fun readAt(
        endSample: Long,
        out: FloatArray,
    ): Boolean

    /** Convenience: the newest available window. */
    fun readLatest(out: FloatArray): Boolean = readAt(writtenSamples, out)

    /**
     * How many samples [writtenSamples] is ahead of what is heard at this moment: what the
     * player has buffered plus the device's output delay. A reader that trails the write
     * head by this much shows a beat when it sounds. It shrinks as the buffered audio plays
     * and grows with each burst written. 0 when the backend cannot tell, and a reader then
     * assumes a delay of its own.
     */
    val aheadSamples: Long get() = 0L

    /** What the output stage last did to the peaks; see [PeakReading]. */
    val peaks: PeakReading get() = PeakReading.NONE
}

/**
 * What the last stage of the audio chain did to the loudest moments.
 *
 * It travels with the tap the visualizers read. It is written at decode time like the
 * samples, so it can run a little ahead of what is heard.
 *
 * @param holdingDb how far the limiter has the volume down right now, 0 or below
 * @param clippedAtNanos [System.nanoTime] of the last peak cut off at full scale,
 *   0 when none has been
 */
data class PeakReading(
    val holdingDb: Float,
    val clippedAtNanos: Long,
) {
    companion object {
        val NONE = PeakReading(0f, 0L)
    }
}

/**
 * Single-writer ring buffer implementation. The writer is the audio thread; readers are
 * render loops. There are no locks, so a reader can see a torn window.
 *
 * It holds several seconds, more than a player buffers ahead, so the window a reader wants
 * [aheadSamples] behind the write head is still in it.
 *
 * The audio thread says now and then which sample is being heard ([reportAhead]). Between
 * two reports that place moves on with the clock, so [aheadSamples] is right at any moment
 * and not only when a report arrives, which matters because a player writes in bursts of
 * a few hundred milliseconds.
 */
class PcmRingBuffer(
    capacity: Int = 262_144,
    /** The clock the heard place moves on with between reports; tests pass their own. */
    private val nanoTime: () -> Long = System::nanoTime,
) : AudioTap {
    private val buffer = FloatArray(capacity)

    @Volatile private var written = 0L

    @Volatile private var rate = 0

    @Volatile override var peaks: PeakReading = PeakReading.NONE
        private set

    /** The sample that was being heard at [heardAtNanos]. */
    @Volatile private var heardSample = 0L

    /** When [heardSample] was heard; [HELD] while the place stands still, [NEVER] before any report. */
    @Volatile private var heardAtNanos = NEVER

    /** The distance at the last report or hold, which is the answer while the place stands still. */
    @Volatile private var heldAhead = 0L

    override val aheadSamples: Long
        get() {
            val at = heardAtNanos
            if (at == NEVER) return 0L
            if (at == HELD) return heldAhead
            val sinceMicros = (nanoTime() - at) / NANOS_PER_MICRO
            // reports come several times a second while audio plays; without one the place
            // is not known to be moving
            if (sinceMicros > STALE_MICROS) return heldAhead
            val heardNow = heardSample + sinceMicros * rate / MICROS_PER_SECOND
            return (written - heardNow).coerceAtLeast(0L)
        }

    override val sampleRateHz: Int get() = rate

    /**
     * Called from the audio thread while audio plays, with how far the write head is ahead
     * of the ear right now; see [AudioTap.aheadSamples].
     */
    fun reportAhead(samples: Long) {
        val ahead = samples.coerceAtLeast(0L)
        heldAhead = ahead
        heardSample = written - ahead
        heardAtNanos = nanoTime()
    }

    /**
     * Called from the audio thread when the sound stops moving, at a pause or a seek: the
     * distance stays what it is now until the next [reportAhead].
     */
    fun holdAhead() {
        if (heardAtNanos == NEVER) return
        heldAhead = aheadSamples
        heardAtNanos = HELD
    }

    /**
     * Called from the audio thread once per buffer with what the output stage did; see
     * [PeakReading]. A new reading is published only when a value changed.
     */
    fun reportPeaks(
        holdingDb: Float,
        clippedAtNanos: Long,
    ) {
        val now = peaks
        if (now.holdingDb != holdingDb || now.clippedAtNanos != clippedAtNanos) peaks = PeakReading(holdingDb, clippedAtNanos)
    }

    override val writtenSamples: Long get() = written

    fun onFormatChanged(sampleRateHz: Int) {
        rate = sampleRateHz
    }

    fun write(
        samples: FloatArray,
        count: Int,
    ) {
        var pos = (written % buffer.size).toInt()
        for (i in 0 until count) {
            buffer[pos] = samples[i]
            pos++
            if (pos == buffer.size) pos = 0
        }
        written += count
    }

    override fun readAt(
        endSample: Long,
        out: FloatArray,
    ): Boolean {
        val end = written
        if (endSample > end || endSample < out.size) return false
        if (endSample - out.size < end - buffer.size) return false // already overwritten
        var pos = ((endSample - out.size) % buffer.size).toInt()
        for (i in out.indices) {
            out[i] = buffer[pos]
            pos++
            if (pos == buffer.size) pos = 0
        }
        return true
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val HELD = Long.MIN_VALUE + 1
        const val NANOS_PER_MICRO = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val STALE_MICROS = 2_000_000L
    }
}
