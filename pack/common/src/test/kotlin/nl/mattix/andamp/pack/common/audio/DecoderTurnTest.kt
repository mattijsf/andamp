// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One codec at a time, on real threads. */
class DecoderTurnTest {
    private val turn = DecoderTurn()

    @Test
    fun `a track waits for the one in front to give its turn`() {
        val first = Any()
        val second = Any()
        assertTrue(turn.tryTake(first))
        val took = CountDownLatch(1)
        val waiter = thread { if (turn.take(second) { false }) took.countDown() }

        assertFalse("the second track waits while the first holds the turn", took.await(WAIT_MS, TimeUnit.MILLISECONDS))
        turn.give(first)

        assertTrue("the second track takes the turn once it is given", took.await(LONG_MS, TimeUnit.MILLISECONDS))
        waiter.join(LONG_MS)
        assertFalse("the first track cannot take the turn back", turn.tryTake(first))
    }

    @Test
    fun `a track let go of while it waits leaves without the turn`() {
        val first = Any()
        assertTrue(turn.tryTake(first))
        val released = AtomicBoolean(false)
        val answer = AtomicInteger(UNANSWERED)
        val waiter = thread { answer.set(if (turn.take(Any()) { released.get() }) TOOK else LEFT) }

        Thread.sleep(WAIT_MS)
        released.set(true)
        turn.wake()
        waiter.join(LONG_MS)

        assertEquals(LEFT, answer.get())
        turn.give(first)
        assertTrue("the departed waiter holds no turn", turn.tryTake(Any()))
    }

    @Test
    fun `a give from anybody who does not hold the turn changes nothing`() {
        val holder = Any()
        assertTrue(turn.tryTake(holder))

        turn.give(Any())

        assertFalse(turn.tryTake(Any()))
        assertTrue("the holder still holds the turn", turn.tryTake(holder))
    }

    @Test
    fun `many tracks racing for it never hold it together`() {
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val workers =
            (1..RACERS).map {
                thread {
                    repeat(ROUNDS) {
                        val me = Any()
                        turn.take(me) { false }
                        most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        inside.decrementAndGet()
                        turn.give(me)
                    }
                }
            }
        workers.forEach { it.join(LONG_MS) }

        assertEquals(1, most.get())
    }

    private fun thread(body: () -> Unit) = Thread(body).apply { start() }

    private companion object {
        const val WAIT_MS = 100L
        const val LONG_MS = 5_000L
        const val RACERS = 8
        const val ROUNDS = 500
        const val UNANSWERED = -1
        const val TOOK = 1
        const val LEFT = 0
    }
}
