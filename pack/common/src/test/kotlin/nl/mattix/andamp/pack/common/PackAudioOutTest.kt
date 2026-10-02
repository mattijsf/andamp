// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import nl.mattix.andamp.core.playback.PcmProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The audio crossing, over a real pipe: `Pipe.open()` is a pair of file descriptors like
 * the one [android.os.ParcelFileDescriptor.createPipe] hands the player. A real pipe makes
 * a write wait when nobody drains it, and lets the far side read its end.
 *
 * The reading end is non-blocking throughout, so a failing test waits out its patience and
 * fails; it cannot hang.
 */
class PackAudioOutTest {
    private val out = PackAudioOut()

    private val engine = FakePcm()

    /** Every pipe opened here, so none is left as an fd when the test ends. */
    private val opened = mutableListOf<Pipe>()

    @After
    fun letGo() {
        out.stop()
        opened.forEach { pipe ->
            runCatching { pipe.source().close() }
            runCatching { pipe.sink().close() }
        }
    }

    @Test
    fun `what the engine decodes comes out of the other end of the pipe`() {
        val player = pipe()
        out.start(engine)
        out.handOver(writeEnd(player))

        engine.give(TUNE)

        assertArrayEquals(TUNE, read(player, TUNE.size))
    }

    /** The player asks for a pipe when it is ready, which may be before anything is decoding. */
    @Test
    fun `a pipe opened before the backend starts is the one the samples arrive on`() {
        val player = pipe()
        out.handOver(writeEnd(player))

        out.start(engine)
        engine.give(TUNE)

        assertArrayEquals(TUNE, read(player, TUNE.size))
    }

    @Test
    fun `a discard ends the pipe`() {
        val player = pipe()
        out.start(engine)
        out.handOver(writeEnd(player))
        engine.give(TUNE)
        read(player, TUNE.size)

        out.discard()

        assertTrue("a discard closes the pipe", ended(player))
        assertEquals("a discard reaches the engine once", 1, engine.discards.get())
    }

    @Test
    fun `a stop ends the pipe as well`() {
        val player = pipe()
        out.start(engine)
        out.handOver(writeEnd(player))
        engine.give(TUNE)
        read(player, TUNE.size)

        out.stop()

        assertTrue("a stop closes the pipe", ended(player))
    }

    @Test
    fun `the pipe the player opens next starts where the music now is`() {
        val before = pipe()
        out.start(engine)
        out.handOver(writeEnd(before))
        engine.give(TUNE)
        read(before, TUNE.size)
        out.discard()

        val after = pipe()
        out.handOver(writeEnd(after))
        engine.give(ELSEWHERE)

        assertArrayEquals(ELSEWHERE, read(after, ELSEWHERE.size))
    }

    /**
     * A read can wait seconds for the engine, and a discard in that time replaces the pipe.
     * The samples that read returns are from before the discard and must not be written to
     * the new pipe.
     */
    @Test
    fun `what was decoded before a discard never reaches the pipe that came after`() {
        // an engine that hands over its stale audio when it should drop it, to exercise the
        // carrier's own guard
        val slow = FakePcm(waitMs = PATIENT_MS, dropsItsOwn = false)
        val before = pipe()
        out.start(slow)
        out.handOver(writeEnd(before))
        awaitThat("the engine is read") { slow.waiting.get() > 0 }

        out.discard()
        val after = pipe()
        out.handOver(writeEnd(after))
        // the waiting read is answered with the audio from before the seek
        slow.give(STALE)
        slow.give(ELSEWHERE)

        assertArrayEquals(ELSEWHERE, read(after, ELSEWHERE.size))
    }

    /**
     * A player that is not draining leaves the write waiting. Closing the pipe under a
     * waiting write must not let an exception out of the carrier thread, which would end
     * the process.
     */
    @Test
    fun `a write nobody drains waits, and closing the pipe under it lets no exception escape`() {
        val crashes =
            crashesDuring {
                val player = pipe()
                out.start(engine)
                out.handOver(writeEnd(player))
                // far more than any pipe holds, with nothing reading the far end
                repeat(PIPEFULS) { engine.give(ByteArray(PACKET_BYTES)) }
                awaitThat("some audio is carried") { engine.left < PIPEFULS }

                assertTrue("the full pipe leaves audio waiting in the engine", engine.left > 0)

                out.discard()
                val after = pipe()
                out.handOver(writeEnd(after))
                engine.give(ELSEWHERE)

                assertArrayEquals("the carrier carries on after the close", ELSEWHERE, read(after, ELSEWHERE.size))
            }

        assertEquals("no exception escapes when the pipe ends: $crashes", emptyList<Throwable>(), crashes)
    }

    /** An engine that throws ends the pipe, and no exception escapes the carrier thread. */
    @Test
    fun `an engine that throws ends the pipe and lets no exception escape`() {
        val player = pipe()

        val crashes =
            crashesDuring {
                out.start(PcmProvider { error("the decoder has gone") })
                out.handOver(writeEnd(player))

                assertTrue("an engine failure closes the pipe", ended(player))
            }

        assertEquals("no exception escapes when the engine throws: $crashes", emptyList<Throwable>(), crashes)
    }

    /** Runs [doing] and answers the exceptions that escaped other threads meanwhile. */
    private fun crashesDuring(doing: () -> Unit): List<Throwable> {
        val crashes = mutableListOf<Throwable>()
        val was = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> synchronized(crashes) { crashes += thrown } }
        try {
            doing()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(was)
        }
        return synchronized(crashes) { crashes.toList() }
    }

    /** A pipe whose reading end never blocks, so no assertion in this file can hang. */
    private fun pipe(): Pipe {
        val made = Pipe.open()
        made.source().configureBlocking(false)
        opened += made
        return made
    }

    /** The write end, as the service hands it to the output. */
    private fun writeEnd(pipe: Pipe): OutputStream = Channels.newOutputStream(pipe.sink())

    /** Up to [count] bytes, waited for; fewer only where the patience ran out or the pipe ended. */
    private fun read(
        pipe: Pipe,
        count: Int,
    ): ByteArray {
        val into = ByteBuffer.allocate(count)
        val giveUp = System.currentTimeMillis() + PATIENCE_MS
        while (into.hasRemaining() && System.currentTimeMillis() < giveUp) {
            if (pipe.source().read(into) < 0) break
            if (into.hasRemaining()) Thread.sleep(POLL_MS)
        }
        into.flip()
        val arrived = ByteArray(into.remaining())
        into.get(arrived)
        return arrived
    }

    /** Whether the far end has been closed, once whatever was still in the pipe has been drained. */
    private fun ended(pipe: Pipe): Boolean {
        val into = ByteBuffer.allocate(PACKET_BYTES)
        val giveUp = System.currentTimeMillis() + PATIENCE_MS
        while (System.currentTimeMillis() < giveUp) {
            into.clear()
            if (pipe.source().read(into) < 0) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** Waits for something a thread of its own has to do, and fails with [what] if it never happens. */
    private fun awaitThat(
        what: String,
        so: () -> Boolean,
    ) {
        val giveUp = System.currentTimeMillis() + PATIENCE_MS
        while (System.currentTimeMillis() < giveUp) {
            if (so()) return
            Thread.sleep(POLL_MS)
        }
        fail(what)
    }

    /**
     * A fake engine: a queue of packets, one per read, and a wait when there is none. A
     * wait that runs out answers zero, and a discard drops what has not been handed over.
     */
    private class FakePcm(
        private val waitMs: Long = WAIT_MS,
        /**
         * Whether this engine drops what it decoded when it is discarded, as the real one
         * does. False makes it hand stale audio over, which is what the carrier's own
         * guard is tested against.
         */
        private val dropsItsOwn: Boolean = true,
    ) : PcmProvider {
        private val packets = LinkedBlockingQueue<ByteArray>()

        val discards = AtomicInteger()

        /** How many reads are waiting right now, so a test can catch one in flight. */
        val waiting = AtomicInteger()

        /** What has been decoded and not yet carried. */
        val left: Int get() = packets.size

        fun give(packet: ByteArray) {
            packets.put(packet)
        }

        override fun read(into: ByteArray): Int {
            waiting.incrementAndGet()
            val packet =
                try {
                    packets.poll(waitMs, TimeUnit.MILLISECONDS)
                } finally {
                    waiting.decrementAndGet()
                }
            // nothing in time, or woken by a discard: both answer zero
            if (packet == null || packet.isEmpty()) return 0
            packet.copyInto(into)
            return packet.size
        }

        /** Drops what was decoded ahead, and wakes a read that is waiting. */
        override fun discard() {
            discards.incrementAndGet()
            if (!dropsItsOwn) return
            packets.clear()
            if (waiting.get() > 0) packets.put(ByteArray(0))
        }

        private companion object {
            /** Long enough to be a wait, short enough that a finished test is not held by one. */
            const val WAIT_MS = 100L
        }
    }

    private companion object {
        /** Two frames of 44.1 kHz stereo, and nothing that could be mistaken for silence. */
        val TUNE = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        /** The same shape, so a pipe carrying the wrong one is caught by its content. */
        val ELSEWHERE = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)
        val STALE = byteArrayOf(-1, -2, -3, -4, -5, -6, -7, -8)

        /** What the carrier reads in, which is also a quarter of a pipe. */
        const val PACKET_BYTES = 16_384

        /** Far more than any pipe holds, so some of it must still be waiting. */
        const val PIPEFULS = 16

        const val PATIENCE_MS = 2_000L
        const val POLL_MS = 10L

        /** A read that is still waiting when the seek arrives; see the discard's own test. */
        const val PATIENT_MS = 5_000L
    }
}
