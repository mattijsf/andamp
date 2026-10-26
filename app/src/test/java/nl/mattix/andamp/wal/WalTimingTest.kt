// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import org.junit.Assert.assertEquals
import org.junit.Test

/** The timing log's line for a tick or a published frame, which measuring scripts read by its words. */
class WalTimingTest {
    @Test
    fun `a tick or a frame not planned names its work, its draw list and its cause`() {
        assertEquals(
            "tick wal-1: tick 1200 ns, publish 0 ns, wake",
            WalTiming.line("wal-1", 1200, 0, WalCause.WAKE),
        )
    }

    @Test
    fun `a planned frame ends with its planning time, and says when a window was planned from the start`() {
        assertEquals(
            "tick wal-1: tick 1200 ns, publish 5000 ns, tick, plan 300 ns",
            WalTiming.line("wal-1", 1200, 5000, WalCause.TICK, planNanos = 300),
        )
        assertEquals(
            "tick wal-1: tick 1200 ns, publish 5000 ns, touch, plan 900 ns from the start",
            WalTiming.line("wal-1", 1200, 5000, WalCause.TOUCH, planNanos = 900, fromStart = true),
        )
    }
}
