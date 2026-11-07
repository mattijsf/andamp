// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/**
 * New classic renderer: a classic skin (.wsz) on the player is drawn by walkit, as Winamp 5.666
 * draws it, instead of by Andamp's own classic renderer. The skin stays a classic skin everywhere
 * else (menus, widget, Android Auto). Kept in the modern-skin preferences ([WalSettings.PREFS])
 * under [KEY]; off unless switched on, and independent of the modern skins switch.
 *
 * The value is read once, off the main thread, before the player's first skin goes on, as
 * [WalApart]'s is. The switch in Preferences calls [change] on main; the player hears it through
 * [on] and puts its skin on again.
 */
internal object WalClassic {
    /** Whether the new classic renderer is on; snapshot state, so the switch follows a change at once. */
    var on by mutableStateOf(false)
        private set

    /** [on] as a plain field, for other threads. */
    @Volatile
    var native: Boolean = false
        private set

    /** Whether [on] holds the stored value or a later change; set under [lock]. */
    @Volatile
    private var known = false

    private val lock = Any()
    private val reading = Any()

    /** See [WalSharp.storedIn]. */
    @Volatile
    private var storedIn: Context? = null

    /**
     * Sets [on] to the stored value, the first time only, and returns it. Reads preferences, so it
     * is called off the main thread.
     */
    fun read(context: Context): Boolean {
        if (known) return native
        val test = storedIn
        if (test != null && (context.applicationContext ?: context) !== test) return native
        synchronized(reading) {
            if (known) return native
            val stored = prefs(test ?: context).getBoolean(KEY, false)
            synchronized(lock) {
                if (!known) apply(stored)
            }
            Snapshot.sendApplyNotifications()
        }
        return native
    }

    /** Sets [on] to [value] and stores it. Called on main. */
    fun change(
        context: Context,
        value: Boolean,
    ) {
        synchronized(lock) { apply(value) }
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    /** Sets everything back to before the first read, so each test starts from nothing read (see [WalSharp.forget]). */
    internal fun forget(context: Context? = null) {
        storedIn = context?.let { it.applicationContext ?: it }
        synchronized(lock) {
            on = false
            native = false
            known = false
        }
        Snapshot.sendApplyNotifications()
    }

    private fun apply(value: Boolean) {
        on = value
        native = value
        known = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)

    /** The key of the stored value in [WalSettings.PREFS]. */
    const val KEY = "classic_native"
}
