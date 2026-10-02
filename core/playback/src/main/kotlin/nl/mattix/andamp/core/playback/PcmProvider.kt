// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

/**
 * Decoded audio offered a buffer at a time: the interface between a backend that decodes
 * and the [AudioOut] that renders.
 *
 * A provider may push back when nobody reads, by blocking or by stalling its own decoder.
 * Whoever holds one keeps reading it until it answers -1.
 */
fun interface PcmProvider {
    /**
     * Fills [into] with the next samples and answers how many bytes it wrote: zero when it
     * had none in time, and -1 once there will be no more.
     *
     * Blocking is expected: the caller is a render loop on its own thread, and the wait is
     * what paces that loop.
     */
    fun read(into: ByteArray): Int

    /**
     * Drops whatever is decoded and not yet read, without blocking: it is the
     * audio from before a seek, a restart or another track. A provider that
     * holds nothing ahead has nothing to drop.
     */
    fun discard() {}

    companion object {
        /**
         * The format of every provider's bytes: 44.1 kHz, stereo, 16-bit PCM. It is fixed,
         * so no format travels with a buffer.
         */
        const val SAMPLE_RATE_HZ = 44_100
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4
    }
}
