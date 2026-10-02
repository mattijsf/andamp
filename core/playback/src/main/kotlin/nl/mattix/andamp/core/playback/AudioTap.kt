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
 */
class PcmRingBuffer(
    capacity: Int = 65536,
) : AudioTap {
    private val buffer = FloatArray(capacity)

    @Volatile private var written = 0L

    @Volatile private var rate = 0

    @Volatile override var peaks: PeakReading = PeakReading.NONE
        private set

    override val sampleRateHz: Int get() = rate

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
}
