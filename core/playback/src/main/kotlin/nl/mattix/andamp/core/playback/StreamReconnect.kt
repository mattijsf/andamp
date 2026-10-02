// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import kotlin.random.Random

/**
 * When to try a dropped station again, and when to stop trying.
 *
 * - Backoff. The first try comes after about [FIRST_DELAY_MS]. Each failure after that
 *   doubles the wait, up to [MAX_DELAY_MS].
 * - Jitter. Every wait is moved by up to [JITTER] of itself either way, so listeners who
 *   dropped together do not all reconnect at the same instant.
 * - A budget. [MAX_ATTEMPTS] tries, or [BUDGET_MS] of trying, whichever runs out first.
 *   After that [failed] answers [Next.GiveUp].
 *
 * Time without a network is not spent. While there is none, [failed] answers
 * [Next.WaitForNetwork] and the budget's clock stands still. When a network comes back the
 * backoff starts from the first wait again ([networkBack]); that try still counts against
 * [MAX_ATTEMPTS].
 *
 * A caller that holds no wake lock while waiting can have a wait stretched by Doze. So that
 * a stretched clock alone cannot end the retrying, the time budget applies only once
 * [MIN_ATTEMPTS] tries have been made.
 *
 * [HEALTHY_MS] of sound between two drops resets the budget.
 *
 * The clock and the random source are passed in, so the policy can be tested on a JVM.
 */
class StreamReconnect(
    private val now: () -> Long,
    private val random: Random = Random.Default,
) {
    /** What to do about a drop. */
    sealed interface Next {
        /** Try again after this long. */
        data class RetryIn(
            val delayMs: Long,
        ) : Next

        /** No network: schedule nothing, and wait to hear that one is back. */
        data object WaitForNetwork : Next

        /** The budget is spent: stop, and say so. */
        data object GiveUp : Next
    }

    /** Tries made since the trouble began; what [MAX_ATTEMPTS] counts. */
    var attempts = 0
        private set

    /** Where on the backoff ladder the next wait is taken from. */
    private var rung = 0

    /** Time spent failing with a network there, over stretches already closed. */
    private var spentMs = 0L

    /** When the stretch of failing now under way began, or null when none is. */
    private var spendingSince: Long? = null

    /** Sound heard since the last drop, over stretches already closed. */
    private var soundMs = 0L

    /** When the sound now playing began, or null when it is silent. */
    private var soundSince: Long? = null

    /** The station is audible: count it towards [HEALTHY_MS], and stop spending the budget. */
    fun sounding() {
        closeSpending()
        if (soundSince == null) soundSince = now()
    }

    /** The station has gone quiet: buffering, paused or dropped. */
    fun silent() {
        soundSince?.let { soundMs += now() - it }
        soundSince = null
    }

    /**
     * The station dropped. [online] is whether a network is there right now.
     *
     * A retry answered here is already counted; the caller only has to make it.
     */
    fun failed(online: Boolean): Next {
        silent()
        if (soundMs >= HEALTHY_MS) forget()
        soundMs = 0
        if (!online) {
            closeSpending()
        } else if (spendingSince == null) {
            spendingSince = now()
        }
        val spent = spentMs + (spendingSince?.let { now() - it } ?: 0)
        return when {
            attempts >= MAX_ATTEMPTS -> Next.GiveUp
            attempts >= MIN_ATTEMPTS && spent >= BUDGET_MS -> Next.GiveUp
            !online -> Next.WaitForNetwork
            else -> Next.RetryIn(nextDelay())
        }
    }

    /** The network went away: the budget's clock stops until it is back. */
    fun networkLost() = closeSpending()

    /**
     * A network is back: the wait before the one try it earns.
     *
     * Short, not zero, to give a network that has just validated time to settle its routes.
     */
    fun networkBack(): Long {
        if (spendingSince == null) spendingSince = now()
        rung = 0
        attempts++
        return SETTLE_MS
    }

    /** Back to no trouble at all: for a listener's own press, or after [HEALTHY_MS] of sound. */
    fun forget() {
        attempts = 0
        rung = 0
        spentMs = 0
        spendingSince = null
        soundMs = 0
        soundSince = soundSince?.let { now() }
    }

    private fun nextDelay(): Long {
        val base = (FIRST_DELAY_MS shl rung.coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_DELAY_MS)
        rung++
        attempts++
        val spread = 1.0 - JITTER + random.nextDouble() * 2 * JITTER
        return (base * spread).toLong()
    }

    private fun closeSpending() {
        spendingSince?.let { spentMs += now() - it }
        spendingSince = null
    }

    companion object {
        /** The first wait. */
        const val FIRST_DELAY_MS = 1_000L

        /** The longest wait between two tries. */
        const val MAX_DELAY_MS = 30_000L

        /** How far either way a wait is moved, as a fraction of it. */
        const val JITTER = 0.2

        /** Tries before giving up, whatever the clock says. */
        const val MAX_ATTEMPTS = 10

        /** Time spent failing, with a network there, before giving up. */
        const val BUDGET_MS = 5 * 60_000L

        /** Tries made before [BUDGET_MS] may end it, so that a clock stretched by Doze alone cannot. */
        const val MIN_ATTEMPTS = 3

        /** Sound between two drops that forgets the trouble before them. */
        const val HEALTHY_MS = 30_000L

        /** The wait after a network comes back, for its routes to settle. */
        const val SETTLE_MS = 750L

        /** The largest shift, which keeps the shift from overflowing. [MAX_DELAY_MS] caps the delay before it is reached. */
        private const val MAX_SHIFT = 16
    }
}
