// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.Handler

/** [WalRest]'s wake on the engine thread's [handler], on [android.os.SystemClock.uptimeMillis]: one [wake] at a time. */
internal class WalTimer(
    private val handler: Handler,
    private val wake: Runnable,
) : WalRest.Timer {
    override fun wakeAt(at: Long) {
        handler.removeCallbacksAndMessages(this)
        handler.postAtTime(wake, this, at)
    }

    override fun cancel() = handler.removeCallbacksAndMessages(this)

    override val waiting: Boolean get() = handler.hasMessages(0, this)
}
