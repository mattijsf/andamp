// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Sharp pixels: a modern skin at a fractional scale is drawn straight onto the screen with hard
 * edges ([WalRender.direct]) instead of at the next whole scale and shrunk. Skins at a whole scale
 * look the same either way. Kept in the modern-skin preferences ([WalSettings.PREFS]) under
 * [KEY]; off unless switched on.
 *
 * The value is read once, off the main thread, before a modern skin draws its first frame: the
 * application's startup thread and the player's check whether modern skins are on both call
 * [read]. The switch in Preferences calls [change] on main, which also draws the skin on screen
 * again at once. A change made while the first read is still loading wins over what that read
 * finds.
 */
internal object WalSharp {
    /** Whether sharp pixels are on; snapshot state, so the switch follows a change at once. */
    var on by mutableStateOf(false)
        private set

    /** Whether [on] holds the stored value or a later change; set under [lock]. */
    @Volatile
    private var known = false

    /** Held while [on] and [known] change, never while the preferences load. */
    private val lock = Any()

    /** Held while the stored value loads, so it loads once. */
    private val reading = Any()

    /** Told after the stored value has loaded and before it is applied; for tests. */
    internal var onLoaded: (() -> Unit)? = null

    /**
     * The application of the running test, set by [forget] for tests; null in the app. A [read]
     * with a context of another application comes from a view model left from an earlier test and
     * does nothing, so the test alone decides when the stored value is read.
     */
    @Volatile
    private var storedIn: Context? = null

    /**
     * Sets [on] and [WalRender.direct] to the stored value, the first time only. Reads preferences,
     * so it is called off the main thread.
     */
    fun read(context: Context) {
        if (known) return
        val test = storedIn
        if (test != null && (context.applicationContext ?: context) !== test) return
        synchronized(reading) {
            if (known) return
            val stored = prefs(test ?: context).getBoolean(KEY, false)
            onLoaded?.invoke()
            synchronized(lock) {
                // a change made while the stored value loaded is newer than it
                if (!known) apply(stored)
            }
            Snapshot.sendApplyNotifications()
        }
    }

    /** Sets [on] and [WalRender.direct] to [value], draws the skin on screen again, and stores it. Called on main. */
    fun change(
        context: Context,
        value: Boolean,
    ) {
        synchronized(lock) { apply(value) }
        WalRender.redraws.intValue++
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    /**
     * Sets everything back to before the first read, and [WalRender.direct] off, so each test starts
     * from nothing read. With a [context], only reads with that application load the stored value
     * (see [storedIn]).
     */
    internal fun forget(context: Context? = null) {
        storedIn = context?.let { it.applicationContext ?: it }
        synchronized(lock) {
            on = false
            known = false
            WalRender.direct = false
        }
        onLoaded = null
        Snapshot.sendApplyNotifications()
    }

    private fun apply(value: Boolean) {
        on = value
        WalRender.direct = value
        known = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)

    /** The key of the stored value in [WalSettings.PREFS]. */
    const val KEY = "sharp_pixels"
}
