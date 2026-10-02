// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacer against a fake clock. The fake sleeper advances the clock, optionally overshooting
 * the way `Thread.sleep` does.
 */
class FramePacerTest {
    private var clock = 0L

    private fun pacer(
        fps: Int,
        overshootNs: Long = 0L,
    ) = FramePacer(fps, nowNs = { clock }, sleepNs = { clock += it + overshootNs })

    @Test
    fun `a loop that renders instantly is held to the target rate`() {
        val pacer = pacer(FPS)
        repeat(FRAMES) { pacer.await() }
        assertEquals(FRAMES * FRAME_NS, clock)
    }

    /**
     * Guards against the deadline being reset when a sleep overshoots, which makes every
     * frame longer by the overshoot.
     */
    @Test
    fun `sleep overshoot does not lower the average rate`() {
        val overshoot = 6_000_000L // 6 ms
        val pacer = pacer(FPS, overshootNs = overshoot)
        repeat(FRAMES) { pacer.await() }

        val measuredFps = FRAMES * NANOS_PER_SECOND.toDouble() / clock
        assertTrue("the average rate $measuredFps fps stays within 5% of $FPS", measuredFps > FPS * 0.95)
    }

    @Test
    fun `a stall resyncs to the current time`() {
        val pacer = pacer(FPS)
        pacer.await()
        clock += FRAME_NS * 100 // a stall of a hundred frames
        pacer.await()
        val afterStall = clock

        // the frames owed are skipped: the next one takes a normal frame
        pacer.await()
        assertEquals(FRAME_NS, clock - afterStall)
    }

    private companion object {
        const val FPS = 60
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val FRAME_NS = NANOS_PER_SECOND / FPS
        const val FRAMES = 200
    }
}
