// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Windows apart: a modern skin's windows set out on the player as on a desktop instead of one
 * stack, each where the listener put it, moved by a drag on its title bar and docking and
 * snapping as Winamp does ([nl.mattix.walkit.Presentation.freeWindows]). Kept in the modern-skin
 * preferences ([WalSettings.PREFS]) under [KEY]; off unless switched on.
 *
 * The value is read once, off the main thread, before a modern skin draws its first frame, as
 * [WalSharp]'s is. The switch in Preferences calls [change] on main; a look showing a skin hears
 * it through [watch] and sets its windows out again.
 */
internal object WalApart {
    /** Whether windows apart is on; snapshot state, so the switch follows a change at once. */
    var on by mutableStateOf(false)
        private set

    /** [on] as a plain field, for the engine thread. */
    @Volatile
    var apart: Boolean = false
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
            val stored = prefs(test ?: context).getBoolean(KEY, false)
            synchronized(lock) {
                if (!known) apply(stored)
            }
            Snapshot.sendApplyNotifications()
        }
    }

    /** Sets [on] to [value], tells the watchers, and stores it. Called on main. */
    fun change(
        context: Context,
        value: Boolean,
    ) {
        synchronized(lock) { apply(value) }
        for (w in watchers) w()
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    /** From now on [watcher] hears each [change]; see [unwatch]. */
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
            apart = false
            known = false
        }
        watchers.clear()
        Snapshot.sendApplyNotifications()
    }

    private fun apply(value: Boolean) {
        on = value
        apart = value
        known = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)

    /** The key of the stored value in [WalSettings.PREFS]. */
    const val KEY = "windows_apart"
}
