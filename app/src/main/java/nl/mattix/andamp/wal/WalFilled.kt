// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A classic skin drawn by walkit fills the screen: its windows one stack across the player, as a
 * modern skin's are, instead of set out apart. The clutter bar's D switches it, in the place of
 * Winamp's Double Size, and lights while it is on. Kept in the modern-skin preferences
 * ([WalSettings.PREFS]) under [KEY]; off (windows apart) unless switched on.
 *
 * The value is read once, off the main thread, before the player's first skin goes on, as
 * [WalApart]'s is. A Double Size stored in [OLD_PREFS] is read as this one when this one is
 * not set, and removed. D calls [toggle] on main; a look showing a skin hears it through [watch] and
 * lays its windows out again.
 */
internal object WalFilled {
    /** Whether a classic skin fills the screen; snapshot state, so what shows it follows a change at once. */
    var on by mutableStateOf(false)
        private set

    /** [on] as a plain field, for the engine thread. */
    @Volatile
    var filled: Boolean = false
        private set

    /** Whether [on] holds the stored value or a later change; set under [lock]. */
    @Volatile
    private var known = false

    private val lock = Any()
    private val reading = Any()

    /** Who hears a change: the looks showing a skin. */
    private val watchers = CopyOnWriteArraySet<() -> Unit>()

    /** See [WalSharp.storedIn]. */
    @Volatile
    private var storedIn: Context? = null

    /** Sets [on] to the stored value, the first time only. Reads preferences, so it is called off the main thread. */
    fun read(context: Context) {
        if (known) return
        val test = storedIn
        if (test != null && (context.applicationContext ?: context) !== test) return
        synchronized(reading) {
            if (known) return
            val stored = carriedOver(test ?: context)
            synchronized(lock) {
                if (!known) apply(stored)
            }
            Snapshot.sendApplyNotifications()
        }
    }

    /** Switches [on], tells the watchers, and stores it. Called on main. */
    fun toggle(context: Context) {
        val value = synchronized(lock) { (!filled).also(::apply) }
        for (w in watchers) w()
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    /** From now on [watcher] hears each [toggle]; see [unwatch]. */
    fun watch(watcher: () -> Unit) {
        watchers.add(watcher)
    }

    fun unwatch(watcher: () -> Unit) {
        watchers.remove(watcher)
    }

    /** Sets everything back to before the first read, so each test starts from nothing read (see [WalSharp.forget]). */
    internal fun forget(context: Context? = null) {
        storedIn = context?.let { it.applicationContext ?: it }
        synchronized(lock) {
            on = false
            filled = false
            known = false
        }
        watchers.clear()
        Snapshot.sendApplyNotifications()
    }

    private fun apply(value: Boolean) {
        on = value
        filled = value
        known = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)

    /** The stored value, with [OLD_PREFS] taken in when this one is not set. */
    private fun carriedOver(context: Context): Boolean {
        val prefs = prefs(context)
        val old = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        if (!old.contains(OLD_ON)) return prefs.getBoolean(KEY, false)
        val filled = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else old.getBoolean(OLD_ON, false)
        prefs.edit().putBoolean(KEY, filled).apply()
        old.edit().clear().apply()
        return filled
    }

    /** The key of the stored value in [WalSettings.PREFS]. */
    const val KEY = "classic_filled"

    /** Where a Double Size of the app's windows is stored, and its key. */
    const val OLD_PREFS = "doublesize"
    const val OLD_ON = "on"
}
