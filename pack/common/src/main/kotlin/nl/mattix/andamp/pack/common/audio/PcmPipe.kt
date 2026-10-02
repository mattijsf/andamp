// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import nl.mattix.andamp.core.playback.PcmProvider
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded byte ring between a decoder and a render loop.
 *
 * A writer that fills it waits, which keeps the decoder from running ahead of what is
 * heard. A reader that finds it empty waits up to [patience] and then answers 0, so its
 * caller is not left blocked on a stream that may never produce again.
 *
 * Both sides wait on a condition. The reader's wait is bounded because its caller,
 * `PackAudioOut`'s carrier thread, treats 0 as "ask again".
 *
 * A read returns whole frames of [grain] bytes: half a frame of interleaved stereo would
 * swap the channels for everything after it.
 *
 * The ring is one array allocated once, so steady use allocates nothing.
 */
internal class PcmPipe(
    capacityBytes: Int = DEFAULT_CAPACITY_BYTES,
    private val grain: Int = PcmProvider.BYTES_PER_FRAME,
    private val patience: Long = DEFAULT_PATIENCE_NANOS,
) {
    /** Guards every field below it. Held briefly, and released across every wait. */
    private val lock = ReentrantLock()

    /** Signalled when bytes leave, so a writer that filled it can carry on. */
    private val roomed: Condition = lock.newCondition()

    /** Signalled when bytes arrive, when the source ends, and when a discard resets everything. */
    private val filled: Condition = lock.newCondition()

    private val ring = ByteArray(maxOf(capacityBytes - capacityBytes % grain, grain))

    /** Where the next read starts; the write position is [head] + [count], wrapped. */
    private var head = 0

    private var count = 0

    /** No more will ever be written. What is buffered is still readable; after that, -1. */
    private var finished = false

    /**
     * Incremented by every [discard] and [close]. A writer waiting on a full pipe
     * remembers the number it started with and drops its bytes when the number has changed.
     */
    private var generation = 0

    /** True once nothing more will ever be written: see [end] and [close]. */
    val isFinished: Boolean get() = lock.withLock { finished }

    /**
     * Writes [length] bytes of [source] from [offset], waiting for room as often as it
     * takes, and answers whether all of them were written.
     *
     * False means a [discard], an [end] or a [close] happened meanwhile and the rest was
     * dropped. A caller tells a discard from the other two with [isFinished].
     */
    fun write(
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        lock.withLock {
            val mine = generation
            var written = 0
            while (written < length) {
                while (count == ring.size && !finished && generation == mine) {
                    if (!waitOn(roomed)) return false
                }
                if (finished || generation != mine) return false
                written += copyIn(source, offset + written, length - written)
                filled.signalAll()
            }
        }
        return true
    }

    /**
     * Takes as many whole frames as fit in [into]: the byte count, 0 when none arrived
     * within [patience], and -1 once there will never be any. This is [PcmProvider.read]'s
     * contract.
     */
    fun read(into: ByteArray): Int {
        val room = into.size - into.size % grain
        if (room == 0) return 0
        lock.withLock {
            var left = patience
            while (count < grain && !finished && left > 0) left = delay(filled, left)
            if (count < grain) return if (finished) END else 0
            val taken = copyOut(into, room)
            roomed.signalAll()
            return taken
        }
    }

    /**
     * Drops everything buffered and wakes both sides, without waiting for either.
     *
     * Called on a seek and on a track change, from any thread; it holds the lock only to
     * reset the counters. The writer learns of it from [generation], the reader finds
     * nothing to take.
     */
    fun discard() =
        lock.withLock {
            head = 0
            count = 0
            generation++
            roomed.signalAll()
            filled.signalAll()
        }

    /**
     * The source is done: what is buffered can still be read, and the read after that
     * answers -1. The end of a track uses this and not [close], so that its last decoded
     * audio is not cut off.
     */
    fun end() =
        lock.withLock {
            finished = true
            roomed.signalAll()
            filled.signalAll()
        }

    /**
     * Stop now: what is buffered is dropped and everybody waiting is woken. A reader
     * blocked in [read] gets -1 at once.
     */
    fun close() =
        lock.withLock {
            finished = true
            head = 0
            count = 0
            generation++
            roomed.signalAll()
            filled.signalAll()
        }

    /** Copies what fits of [length] into the ring, wrapping once at most. Under [lock]. */
    private fun copyIn(
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val take = minOf(ring.size - count, length)
        val tail = (head + count) % ring.size
        val first = minOf(take, ring.size - tail)
        System.arraycopy(source, offset, ring, tail, first)
        if (first < take) System.arraycopy(source, offset + first, ring, 0, take - first)
        count += take
        return take
    }

    /** Copies out at most [room] bytes, rounded down to whole frames. Under [lock]. */
    private fun copyOut(
        into: ByteArray,
        room: Int,
    ): Int {
        val take = minOf(room, count - count % grain)
        val first = minOf(take, ring.size - head)
        System.arraycopy(ring, head, into, 0, first)
        if (first < take) System.arraycopy(ring, 0, into, first, take - first)
        head = (head + take) % ring.size
        count -= take
        return take
    }

    /**
     * Waits on [condition] until signalled; false when the thread was interrupted. The
     * interrupt flag is set again, so the thread's owner still sees it.
     */
    private fun waitOn(condition: Condition): Boolean =
        try {
            condition.await()
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    /** Waits at most [left] nanoseconds and answers what is left of it; 0 on an interrupt. */
    private fun delay(
        condition: Condition,
        left: Long,
    ): Long =
        try {
            condition.awaitNanos(left)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            0L
        }

    companion object {
        /** [PcmProvider.read]'s "there will be no more". */
        const val END = -1

        /**
         * About 0.37 s of 44.1 kHz stereo, and four reads of `PackAudioOut`'s carrier
         * thread. A seek discards at most this much decoded audio.
         */
        const val DEFAULT_CAPACITY_BYTES = 64 * 1024

        /** How long a read waits before answering 0: 120 ms. */
        const val DEFAULT_PATIENCE_NANOS = 120_000_000L
    }
}
