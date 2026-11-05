// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/**
 * The native engine: a modern skin runs on walkit ([WalkitEngine], the engine in native code)
 * instead of the Kotlin engine. Kept in the modern-skin preferences ([WalSettings.PREFS]) under
 * [KEY]; off unless switched on. Read once off the main thread, as [WalSharp] is; a change applies
 * to the next skin the player puts on ([WalLook.build] reads [on] when it makes the runtime).
 */
internal object WalNative {
    /** Whether the native engine is on; snapshot state, so the switch follows a change at once. */
    var on by mutableStateOf(false)
        private set

    /** Whether [on] holds the stored value or a later change. */
    @Volatile
    private var known = false

    private val lock = Any()

    /** Sets [on] to the stored value, the first time only. Reads preferences, so it is called off the main thread. */
    fun read(context: Context) {
        if (known) return
        val stored = prefs(context).getBoolean(KEY, false)
        synchronized(lock) {
            // a change made while the stored value loaded is newer than it
            if (!known) apply(stored)
        }
        Snapshot.sendApplyNotifications()
    }

    /** Sets [on] to [value] and stores it. Called on main. */
    fun change(
        context: Context,
        value: Boolean,
    ) {
        synchronized(lock) { apply(value) }
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    /** Sets everything back to before the first read, for tests. */
    internal fun forget() {
        synchronized(lock) {
            on = false
            known = false
        }
        Snapshot.sendApplyNotifications()
    }

    private fun apply(value: Boolean) {
        on = value
        known = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)

    /** The key of the stored value in [WalSettings.PREFS]. */
    const val KEY = "native_engine"
}
