// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

/**
 * When a modern skin rests, as its engine works it out on the engine thread: whether the screen
 * should tick it on every display frame ([wantsFrames]), and, while it should not, when the engine
 * wakes the skin itself for what is due next ([plan]).
 *
 * A skin that says something moves is ticked on every display frame, until nothing it shows has
 * changed for [STATIC_AFTER_MS]: then it counts as static, and is woken as one that wants no frames.
 * A wake comes when the skin is next due ([WalIdle.nextDueAt]); a skin that wants frames but went
 * static is looked at as if it were due at once. Wakes keep a gap after the tick before them:
 * [LIVE_GAP_MS] while the music plays or is paused, so a skin timer keeps its rate, and
 * [REST_GAP_MS] while it is stopped or the skin is static, so a stopped skin steps four times a
 * second. A wake is never set more than [MAX_AHEAD_MS] ahead, so a wall clock that jumps back
 * delays nothing for long. Right after a tick from a display frame, the next wake waits at least
 * [SCREEN_GAP_MS], so it does not fall between the display frames of a loop that runs on.
 *
 * Times are the [timer]'s clock in ms (on Android, uptime), apart from the
 * runtime's clock, of which only the distance to [WalIdle.nextDueAt] is read.
 */
internal class WalRest(
    /** Where the wake is set; it runs the engine's tick when it comes. */
    private val timer: Timer,
) {
    /** One wake at a time, which runs the engine's tick at the time set; on Android a Handler's. */
    interface Timer {
        /** Sets the wake for [at], on the timer's clock; the one set before is dropped first. */
        fun wakeAt(at: Long)

        /** Drops the wake set, if any; from any thread. */
        fun cancel()

        /** Whether a wake is set; from any thread. */
        val waiting: Boolean
    }

    /** When the last tick began. */
    var tickAt: Long = 0L
        private set

    /** Whether the last tick came from a display frame. */
    private var fromScreen = false

    /** When something the skin shows last changed; a tick that changed nothing leaves it. */
    var changedAt: Long = 0L

    /** A tick began at [now]; [fromScreen] when a display frame asked for it. */
    fun ticked(
        now: Long,
        fromScreen: Boolean,
    ) {
        tickAt = now
        this.fromScreen = fromScreen
    }

    /** Whether nothing the skin shows has changed for [STATIC_AFTER_MS] at [now]. */
    fun static(now: Long): Boolean = now - changedAt >= STATIC_AFTER_MS

    /** Whether the screen should tick the skin on every display frame at [now], as [idle] says it moves. */
    fun wantsFrames(
        idle: WalIdle,
        now: Long,
    ): Boolean = idle.framesWanted && !static(now)

    /**
     * When the engine should wake the skin, or null for no wake: [idle] read at [now], when the
     * runtime's clock read [runtimeNow].
     */
    fun wakeAt(
        idle: WalIdle,
        now: Long,
        runtimeNow: Long,
    ): Long? {
        // a skin that says it moves gets here once static: it is looked at as soon as the gap allows,
        // whatever its timers say
        val due = if (idle.framesWanted) runtimeNow else idle.nextDueAt ?: return null
        val rest = if (idle.stopped || static(now)) REST_GAP_MS else LIVE_GAP_MS
        val gap = if (fromScreen) maxOf(rest, SCREEN_GAP_MS) else rest
        return maxOf(now + (due - runtimeNow).coerceAtLeast(0), tickAt + gap).coerceAtMost(now + MAX_AHEAD_MS)
    }

    /** Sets the next wake for [idle] (see [wakeAt]) in place of the one set before; null only drops that one. */
    fun plan(
        idle: WalIdle?,
        now: Long,
        runtimeNow: Long,
    ) {
        cancel()
        val at = idle?.let { wakeAt(it, now, runtimeNow) } ?: return
        timer.wakeAt(at)
    }

    /** Drops the wake set, if any; from any thread. */
    fun cancel(): Unit = timer.cancel()

    /** Whether a wake is set; from any thread. */
    val waiting: Boolean get() = timer.waiting

    companion object {
        /** How long nothing a skin shows changes before it counts as static. */
        const val STATIC_AFTER_MS: Long = 1_000L

        /** The gap after a tick before a wake while the music is stopped or the skin static: four a second. */
        const val REST_GAP_MS: Long = 250L

        /** The gap after a tick before a wake while the music plays or is paused: about one display frame. */
        const val LIVE_GAP_MS: Long = 16L

        /** The gap after a tick from a display frame before a wake: about three display frames. */
        const val SCREEN_GAP_MS: Long = 50L

        /** How far ahead a wake is set at most. */
        const val MAX_AHEAD_MS: Long = 1_000L
    }
}
