// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.StreamReconnect

/**
 * Picks a song back up where its connection dropped, on [StreamReconnect]'s schedule.
 *
 * [StreamReconnect] decides when; this class keeps the timer and the network watch, and
 * [StreamBackend] does the opening. A drop leaves the row where it is and the transport
 * Playing. The backend sets `connecting` from [pending], and [redial] is the backend
 * opening the same track again at the place the listener had heard up to.
 *
 * It runs only while the listener wants sound. Every verb that changes what they asked for
 * (pause, stop, a skip, a seek, a new queue, a release) calls [cancel], which also resets
 * the policy's budget.
 *
 * The wait is a coroutine delay on the backend's dispatcher, and no wake lock is taken for
 * it. With the screen off a wait can stretch, which means fewer tries.
 *
 * Nothing is tried without a network. While there is none no timer is set; the network
 * watch is registered only while a resume is pending, and the try comes
 * [StreamReconnect.SETTLE_MS] after a network arrives.
 */
internal class StreamResume(
    private val scope: CoroutineScope,
    private val network: NetworkWatch,
    private val policy: StreamReconnect,
    /** Open the dropped track again at the place it was heard up to. */
    private val redial: () -> Unit,
    /** The budget is spent: the backend skips the row and says so. */
    private val gaveUp: () -> Unit,
    /** Something [pending] reads changed; the backend redraws `connecting`. */
    private val changed: () -> Unit,
) {
    /** A drop is being recovered from: waiting for a try, or making one. */
    var pending = false
        private set

    /** A try has been made and has neither been heard nor dropped yet. */
    private var reaching = false

    private var timer: Job? = null

    /** Music is being heard: it counts towards [StreamReconnect.HEALTHY_MS], and a pending resume is over. */
    fun sounding() {
        policy.sounding()
        if (pending) settle()
    }

    /** The music went quiet because of the listener: a pause or a stop. */
    fun silent() = policy.silent()

    /**
     * The playing track dropped. A try is scheduled, or the network is waited for, or the
     * budget is spent and [gaveUp] is called. The budget is not reset then, so the next
     * row does not get a new one over the same lost connection.
     */
    fun dropped() {
        timer?.cancel()
        timer = null
        reaching = false
        val next = policy.failed(network.online)
        if (next == StreamReconnect.Next.GiveUp) {
            standDown()
            gaveUp()
            return
        }
        val began = !pending
        pending = true
        network.watch { online -> scope.launch { networkChanged(online) } }
        // without a network no timer is set; the watch above brings the next try
        if (next is StreamReconnect.Next.RetryIn) tryAfter(next.delayMs)
        if (began) changed()
    }

    /** The listener asked for something else: no retry, and a fresh budget for the next drop. */
    fun cancel() {
        policy.forget()
        standDown()
    }

    private fun networkChanged(online: Boolean) {
        if (!pending) return
        if (!online) {
            // no network to try on: the timer is cancelled, and a try already under way
            // fails and comes back as a drop
            policy.networkLost()
            timer?.cancel()
            timer = null
            return
        }
        if (reaching) return
        tryAfter(policy.networkBack())
    }

    private fun tryAfter(delayMs: Long) {
        timer?.cancel()
        timer =
            scope.launch {
                delay(delayMs)
                timer = null
                if (!pending) return@launch
                reaching = true
                redial()
            }
    }

    /** Heard again: nothing more to wait for, and nothing more to watch. */
    private fun settle() = standDown()

    /** No timer, no watch, and no longer pending; the budget is left as it is. */
    private fun standDown() {
        timer?.cancel()
        timer = null
        reaching = false
        if (!pending) return
        pending = false
        network.unwatch()
        changed()
    }
}
