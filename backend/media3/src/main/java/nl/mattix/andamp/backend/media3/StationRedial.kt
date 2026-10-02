// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.StreamReconnect

/**
 * Reconnects a dropped station on ExoPlayer.
 *
 * [StreamReconnect] decides when; this does it. A drop (a network error, or a
 * live stream that ended because the server hung up) leaves the row where it
 * is and the transport saying Playing, and the same item is prepared again
 * later. The backend's state is not touched here; the backend reads [pending]
 * to show "connecting".
 *
 * It runs only while the listener wants sound. Every verb that changes what
 * they asked for (pause, stop, a skip, a new queue, Exit) calls [cancel], and
 * so does playWhenReady going false from outside.
 *
 * Nothing is kept awake to wait. The wait is a coroutine delay, which does
 * not wake a sleeping CPU, so with the screen off a wait can stretch until
 * something else wakes the phone. That means fewer tries, never more.
 *
 * Nothing is tried without a network. While there is none no timer is set:
 * the network is watched, only while a reconnect is pending, and the try comes
 * after one arrives; see [StreamReconnect].
 */
internal class StationRedial(
    private val player: Player,
    private val scope: CoroutineScope,
    private val network: () -> NetworkWatch,
    private val policy: StreamReconnect,
    /** Called when the budget is spent; the backend stops the row and tells the listener. */
    private val gaveUp: () -> Unit,
    /** Called when [pending] changes; the backend updates "connecting". */
    private val changed: () -> Unit,
) {
    /** A drop is being recovered from: waiting for a try, or making one. */
    var pending = false
        private set

    /** The item has been prepared again and has not yet answered. */
    private var reaching = false

    private var timer: Job? = null

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    policy.silent()
                    return
                }
                policy.sounding()
                if (pending) settle()
            }
        }

    init {
        player.addListener(listener)
    }

    /**
     * The playing station dropped. The item stays where it is; a try is
     * scheduled, or the network is waited for, or the budget is spent and
     * [gaveUp] is called.
     */
    fun dropped() {
        timer?.cancel()
        reaching = false
        val watch = network()
        val next = policy.failed(watch.online)
        if (next == StreamReconnect.Next.GiveUp) {
            android.util.Log.i(TAG, "Station did not come back after ${policy.attempts} tries")
            cancel()
            gaveUp()
            return
        }
        pending = true
        watch.watch(::networkChanged)
        if (next is StreamReconnect.Next.RetryIn) {
            android.util.Log.i(TAG, "Station dropped; trying again in ${next.delayMs} ms")
            tryAfter(next.delayMs)
        } else {
            android.util.Log.i(TAG, "Station dropped with no network; waiting for one")
        }
        changed()
    }

    /** The listener asked for something else: no retry, and a fresh budget for the next drop. */
    fun cancel() {
        policy.forget()
        if (!pending) return
        pending = false
        reaching = false
        timer?.cancel()
        timer = null
        network().unwatch()
        changed()
    }

    fun release() {
        cancel()
        player.removeListener(listener)
    }

    private fun networkChanged(online: Boolean) {
        if (!pending) return
        if (!online) {
            // nothing to try on: the timer is canceled, and a try already
            // under way fails and comes back as a drop
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
                redial()
            }
    }

    /**
     * Prepares the same item again, at its default position: the start of an
     * Icecast stream, the live edge of an HLS one.
     */
    private fun redial() {
        if (!pending) return
        reaching = true
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        player.seekToDefaultPosition()
        player.prepare()
    }

    /** Heard again: the reconnect is over, and the watch on the network ends. */
    private fun settle() {
        android.util.Log.i(TAG, "Station is back")
        pending = false
        reaching = false
        timer?.cancel()
        timer = null
        network().unwatch()
        changed()
    }

    companion object {
        private const val TAG = "StationRedial"

        /** Server statuses worth asking again. */
        private const val REQUEST_TIMEOUT = 408
        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERRORS = 500

        /**
         * Whether a player error is the kind a station recovers from.
         *
         * True for a network failure and for a server status that may pass
         * (408, 429, 5xx). Other errors, such as 404, 403 or content that is
         * not audio, give the same answer on every try.
         */
        fun heals(error: PlaybackException): Boolean =
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_TIMEOUT,
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                -> true

                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> passingStatus(error)

                else -> false
            }

        private fun passingStatus(error: PlaybackException): Boolean {
            val status =
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                    ?.responseCode ?: return false
            return status >= SERVER_ERRORS || status == REQUEST_TIMEOUT || status == TOO_MANY_REQUESTS
        }
    }
}
