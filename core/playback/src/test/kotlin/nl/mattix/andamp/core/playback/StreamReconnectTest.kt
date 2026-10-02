// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import nl.mattix.andamp.core.playback.StreamReconnect.Companion.BUDGET_MS
import nl.mattix.andamp.core.playback.StreamReconnect.Companion.FIRST_DELAY_MS
import nl.mattix.andamp.core.playback.StreamReconnect.Companion.HEALTHY_MS
import nl.mattix.andamp.core.playback.StreamReconnect.Companion.JITTER
import nl.mattix.andamp.core.playback.StreamReconnect.Companion.MAX_ATTEMPTS
import nl.mattix.andamp.core.playback.StreamReconnect.Companion.MAX_DELAY_MS
import nl.mattix.andamp.core.playback.StreamReconnect.Companion.SETTLE_MS
import nl.mattix.andamp.core.playback.StreamReconnect.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The reconnect policy on a clock the test moves: a dropped station is retried soon, then
 * less often, never without a network, and not forever.
 */
class StreamReconnectTest {
    private var clock = 0L

    private fun policy(random: Random = Random(7)) = StreamReconnect({ clock }, random)

    /** A random source that always answers the same fraction, to pin jitter to one edge. */
    private class Fixed(
        private val fraction: Double,
    ) : Random() {
        override fun nextBits(bitCount: Int) = 0

        override fun nextDouble() = fraction
    }

    private fun StreamReconnect.retry(): Long {
        val next = failed(online = true)
        assertTrue("a failure while online schedules a retry: $next", next is Next.RetryIn)
        return (next as Next.RetryIn).delayMs.also { clock += it }
    }

    @Test
    fun `the waits double from a second and stop growing at the cap`() {
        val middle = policy(Fixed(0.5))

        val waits = List(7) { middle.retry() }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, MAX_DELAY_MS, MAX_DELAY_MS), waits)
    }

    @Test
    fun `jitter moves every wait by at most a fifth either way`() {
        repeat(200) { seed ->
            clock = 0
            val subject = policy(Random(seed))
            var base = FIRST_DELAY_MS
            repeat(MAX_ATTEMPTS) {
                val wait = subject.retry()
                val low = (base * (1 - JITTER)).toLong()
                val high = (base * (1 + JITTER)).toLong()
                assertTrue("wait $wait lies within $low..$high", wait in low..high)
                base = (base * 2).coerceAtMost(MAX_DELAY_MS)
            }
        }
    }

    @Test
    fun `the lowest and highest jitter land on the edges`() {
        assertEquals(800L, policy(Fixed(0.0)).retry())
        assertEquals(1_199L, policy(Fixed(0.9999)).retry())
    }

    @Test
    fun `it gives up after ten tries however quickly they fail`() {
        val subject = policy()
        repeat(MAX_ATTEMPTS) { subject.retry() }

        assertEquals(Next.GiveUp, subject.failed(online = true))
    }

    @Test
    fun `it gives up once five minutes have gone on trying, even with tries left`() {
        val subject = policy(Fixed(0.5))
        repeat(3) { subject.retry() }
        // the time the failed connections took on top of the waits
        clock += BUDGET_MS

        assertEquals(Next.GiveUp, subject.failed(online = true))
    }

    /** Under Doze the clock runs on while a wait sleeps; that alone must not end the retrying. */
    @Test
    fun `a clock stretched past the budget does not end it before a few tries`() {
        val subject = policy()
        subject.retry()
        clock += BUDGET_MS * 2

        assertTrue(subject.failed(online = true) is Next.RetryIn)
    }

    @Test
    fun `without a network nothing is scheduled and no try is spent`() {
        val subject = policy()

        repeat(50) {
            assertEquals(Next.WaitForNetwork, subject.failed(online = false))
            clock += 60_000
        }

        assertEquals(0, subject.attempts)
    }

    /** Time offline is not counted against the budget, and the backoff restarts when the network returns. */
    @Test
    fun `a long spell offline does not use up the budget, and the network's return earns one prompt try`() {
        val subject = policy(Fixed(0.5))
        repeat(3) { subject.retry() }
        clock += 20_000
        subject.networkLost()
        assertEquals(Next.WaitForNetwork, subject.failed(online = false))

        clock += 10 * 60_000 // far longer offline than the whole budget
        val settle = subject.networkBack()
        clock += settle

        assertEquals(SETTLE_MS, settle)
        val after = subject.failed(online = true)
        assertTrue("offline time does not count against the budget: $after", after is Next.RetryIn)
        assertEquals("the backoff restarts from the first delay", FIRST_DELAY_MS, (after as Next.RetryIn).delayMs)
    }

    @Test
    fun `a flapping network runs out of tries`() {
        val subject = policy()
        var gaveUp = false
        repeat(MAX_ATTEMPTS * 2) {
            if (gaveUp) return@repeat
            subject.networkBack()
            gaveUp = subject.failed(online = false) == Next.GiveUp
        }

        assertTrue(gaveUp)
    }

    @Test
    fun `half a minute of sound between two drops forgets the trouble before them`() {
        val subject = policy(Fixed(0.5))
        repeat(MAX_ATTEMPTS - 1) { subject.retry() }

        subject.sounding()
        clock += HEALTHY_MS
        val next = subject.failed(online = true)

        assertEquals(Next.RetryIn(FIRST_DELAY_MS), next)
        assertEquals(1, subject.attempts)
    }

    @Test
    fun `a station that keeps playing a few seconds and dropping still runs out`() {
        val subject = policy()
        var gaveUp = false
        repeat(MAX_ATTEMPTS + 1) {
            if (gaveUp) return@repeat
            val next = subject.failed(online = true)
            gaveUp = next == Next.GiveUp
            subject.sounding()
            clock += 5_000
        }

        assertTrue(gaveUp)
    }

    @Test
    fun `forgetting starts the budget afresh`() {
        val subject = policy()
        repeat(MAX_ATTEMPTS) { subject.retry() }

        subject.forget()

        assertTrue(subject.failed(online = true) is Next.RetryIn)
        assertEquals(1, subject.attempts)
    }
}
