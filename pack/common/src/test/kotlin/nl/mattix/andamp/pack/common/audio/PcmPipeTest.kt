// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The pipe's back-pressure, on real threads: a writer waits on a full pipe, a reader does
 * not wait forever on an empty one, a discard releases both, and an end is distinct from a
 * stall.
 *
 * [park] waits until a thread is observably in its wait before the test does what should
 * end it.
 */
class PcmPipeTest {
    @Test
    fun `a full pipe makes the decoder wait, and a read lets it go`() {
        val pipe = PcmPipe(capacityBytes = 16, patience = BRIEF)
        val finished = CountDownLatch(1)
        val landed = AtomicBoolean(false)

        // 24 bytes into a pipe that holds 16: the writer gets 16 across and waits with the
        // rest
        park(write(pipe, ByteArray(24), finished, landed), Thread.State.WAITING)
        assertEquals(1, finished.count)

        // a read makes room, and the rest follows
        assertEquals(16, pipe.read(ByteArray(16)))
        assertTrue(finished.await(PATIENCE_MS, TimeUnit.MILLISECONDS))
        assertTrue(landed.get())
        assertEquals(8, pipe.read(ByteArray(16)))
    }

    @Test
    fun `a discard empties the pipe and releases both sides`() {
        val pipe = PcmPipe(capacityBytes = 16, patience = BRIEF)
        val finished = CountDownLatch(1)
        val landed = AtomicBoolean(true)

        park(write(pipe, ByteArray(24), finished, landed), Thread.State.WAITING)

        pipe.discard()

        // the writer is woken and told that its bytes were dropped
        assertTrue(finished.await(PATIENCE_MS, TimeUnit.MILLISECONDS))
        assertFalse(landed.get())
        // and nothing of it is left to be read
        assertEquals(0, pipe.read(ByteArray(16)))
    }

    @Test
    fun `a read of an empty open pipe answers zero`() {
        val pipe = PcmPipe(patience = BRIEF)

        val began = System.nanoTime()
        val read = pipe.read(ByteArray(16))

        assertEquals(0, read)
        assertTrue(System.nanoTime() - began < PATIENCE_MS * NANOS_PER_MILLI)
    }

    @Test
    fun `an ended pipe gives what is left and then minus one`() {
        val pipe = PcmPipe(patience = BRIEF)
        pipe.write(byteArrayOf(1, 2, 3, 4), 0, 4)

        pipe.end()

        // what was written before the end is still read
        assertEquals(4, pipe.read(ByteArray(16)))
        assertEquals(-1, pipe.read(ByteArray(16)))
        assertTrue(pipe.isFinished)
    }

    @Test
    fun `closing drops what is buffered`() {
        val pipe = PcmPipe(patience = BRIEF)
        pipe.write(byteArrayOf(1, 2, 3, 4), 0, 4)

        pipe.close()

        // a close drops what is buffered, unlike an end
        assertEquals(-1, pipe.read(ByteArray(16)))
    }

    @Test
    fun `closing unblocks a reader that is already waiting`() {
        val pipe = PcmPipe(patience = TimeUnit.SECONDS.toNanos(30))
        val read = intArrayOf(0)
        val done = CountDownLatch(1)
        val reader =
            Thread {
                read[0] = pipe.read(ByteArray(16))
                done.countDown()
            }
        reader.isDaemon = true
        reader.start()
        park(reader, Thread.State.TIMED_WAITING)

        pipe.close()

        // a reader in a thirty second wait is woken by the close
        assertTrue(done.await(PATIENCE_MS, TimeUnit.MILLISECONDS))
        assertEquals(-1, read[0])
    }

    @Test
    fun `a read never hands back half a frame`() {
        val pipe = PcmPipe(patience = BRIEF)
        pipe.write(ByteArray(8) { it.toByte() }, 0, 8)

        // seven bytes of room hold one whole frame; the other three bytes are left alone
        val into = ByteArray(7)
        assertEquals(4, pipe.read(into))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 0, 0, 0), into)
    }

    @Test
    fun `bytes come out in the order they went in, wrapping the ring`() {
        val pipe = PcmPipe(capacityBytes = 16, patience = BRIEF)
        val written = ByteArray(12) { (it + 1).toByte() }

        pipe.write(written, 0, 12)
        val first = ByteArray(8)
        assertEquals(8, pipe.read(first))
        // the next write runs off the end of the ring and round to its start
        pipe.write(written, 0, 12)

        val rest = ByteArray(16)
        assertEquals(16, pipe.read(rest))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), first)
        assertArrayEquals(byteArrayOf(9, 10, 11, 12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), rest)
    }

    /**
     * Waits until [thread] is in [state], and asserts it. A writer on a full pipe waits
     * without a deadline and a reader on an empty one waits with one, so the two park in
     * different states.
     */
    private fun park(
        thread: Thread,
        state: Thread.State,
    ) {
        val until = System.nanoTime() + PATIENCE_MS * NANOS_PER_MILLI
        while (thread.state != state && System.nanoTime() < until) Thread.yield()
        assertEquals(state, thread.state)
    }

    /** A writer on a thread of its own, which records whether its bytes landed. */
    private fun write(
        pipe: PcmPipe,
        source: ByteArray,
        finished: CountDownLatch,
        landed: AtomicBoolean,
    ): Thread {
        val writer =
            Thread {
                landed.set(pipe.write(source, 0, source.size))
                finished.countDown()
            }
        writer.isDaemon = true
        writer.start()
        return writer
    }

    private companion object {
        /** A read patience of 20 ms, to keep the tests short. */
        const val BRIEF = 20_000_000L
        const val PATIENCE_MS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
