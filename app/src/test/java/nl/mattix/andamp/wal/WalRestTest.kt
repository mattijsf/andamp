// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When a modern skin rests: whether the screen ticks it on every display frame, and when the
 * engine wakes it itself. Times in ms; the runtime's clock runs [SKEW] ahead of uptime, as a wall
 * clock does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalRestTest {
    private val rest = WalRest(Handler(Looper.getMainLooper())) {}

    private fun idle(
        due: Long?,
        framesWanted: Boolean = false,
        stopped: Boolean = false,
    ) = WalIdle(framesWanted, due?.let { it + SKEW }, stopped)

    private fun wakeAt(
        idle: WalIdle,
        now: Long,
    ) = rest.wakeAt(idle, now, now + SKEW)

    @Test
    fun `a playing skin is woken when it is due, a display frame after the last tick at the soonest`() {
        rest.changedAt = 1_000
        rest.ticked(1_000, fromScreen = false)

        assertEquals("a timer due in 17 ms", 1_017L, wakeAt(idle(due = 1_017), 1_000))
        assertEquals("one due at once waits a frame", 1_000L + WalRest.LIVE_GAP_MS, wakeAt(idle(due = 990), 1_000))
    }

    @Test
    fun `a stopped skin is woken four times a second at most`() {
        rest.changedAt = 1_000
        rest.ticked(1_000, fromScreen = false)

        assertEquals(1_250L, wakeAt(idle(due = 1_017, stopped = true), 1_000))
        assertEquals("one due later keeps its time", 1_400L, wakeAt(idle(due = 1_400, stopped = true), 1_000))
    }

    @Test
    fun `a skin that changed nothing for a second is static`() {
        rest.changedAt = 1_000
        rest.ticked(2_000, fromScreen = false)
        val moves = idle(due = null, framesWanted = true)

        assertTrue("it moves", rest.wantsFrames(moves, 1_999))
        assertFalse("static after a second", rest.wantsFrames(moves, 2_000))
        assertEquals("woken as if due at once, four times a second", 2_250L, wakeAt(moves, 2_000))
        assertEquals("its timers too", 2_250L, wakeAt(idle(due = 2_017), 2_000))
    }

    @Test
    fun `a skin with nothing due and nothing moving is not woken`() {
        rest.ticked(1_000, fromScreen = false)

        assertNull(wakeAt(idle(due = null), 1_000))
    }

    @Test
    fun `a wake is set no more than a second ahead`() {
        rest.changedAt = 1_000
        rest.ticked(1_000, fromScreen = false)

        assertEquals(1_000L + WalRest.MAX_AHEAD_MS, wakeAt(idle(due = 60_000), 1_000))
    }

    @Test
    fun `right after a tick from a display frame the next wake leaves room for the next frame`() {
        rest.changedAt = 1_000
        rest.ticked(1_000, fromScreen = true)

        assertEquals(1_000L + WalRest.SCREEN_GAP_MS, wakeAt(idle(due = 1_017), 1_000))
    }

    @Test
    fun `planning sets one wake, and nothing to plan drops it`() {
        rest.changedAt = 1_000
        rest.ticked(1_000, fromScreen = false)

        rest.plan(idle(due = 1_100), 1_000, 1_000 + SKEW)
        rest.plan(idle(due = 1_200), 1_000, 1_000 + SKEW)
        assertTrue(rest.waiting)

        rest.plan(null, 1_000, 1_000 + SKEW)
        assertFalse(rest.waiting)
    }

    private companion object {
        const val SKEW = 1_700_000_000_000L
    }
}
