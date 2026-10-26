// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Whether modern skins may be worn. While it is off, nothing reaches the modern-skin engine
 * through the player.
 *
 * The value is kept in the modern-skin preferences ([WalSettings.PREFS]) under [KEY], which
 * Android backs up with the other settings, so a phone restored from a backup gets the switch back
 * with the skins' files. With nothing stored it reads off, in every build. The switch in Preferences
 * changes it ([change]), and the offer to add a modern skin turns it on ([write]).
 *
 * The first read also moves a value held in the file [OLD_FILE] under
 * [Context.getNoBackupFilesDir] into the preferences, when they hold none, and deletes the file.
 *
 * The application reads the stored value once at startup, on a background thread. [read] may be
 * called again from anywhere off the main thread: after the first time it gives the value at once.
 * Code that decides at startup whether a modern skin goes on calls [read], which waits for the
 * stored value; [on] reads off until then.
 *
 * [change] and [write] set [on] at once and store the value on one background thread, in the order
 * of the calls, so the last call made is the value stored. A change made while the first read is
 * still loading wins over what that read finds.
 *
 * [on] is snapshot state, so a composable that reads it follows a switch at once. Code outside a
 * composition follows it with `snapshotFlow { WalGate.on }`; that is how SkinOps puts a modern skin
 * on or takes it off when the switch changes, while the process runs.
 */
object WalGate {
    /** True while modern skins may be worn. False until the stored value has been read or changed. */
    var on by mutableStateOf(false)
        private set

    /** Whether [on] holds the stored value or a later change; set under [lock]. */
    @Volatile
    private var known = false

    /** Held while [on] and [known] change, never while storage is read or written: a change on main waits for no read. */
    private val lock = Any()

    /** Held while the stored value loads, so it loads once. */
    private val reading = Any()

    /**
     * Stores the values [change] and [write] set, one after another, in the order of the calls, and
     * loads the stored value for [read] in that same order.
     */
    private val store: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "wal-gate").apply { isDaemon = true } }
    }

    /** Told after the stored value has loaded and before it is applied; for tests. */
    internal var onLoaded: (() -> Unit)? = null

    /**
     * The application whose stored value [read] loads, whatever context it is given: set by
     * [forget] for tests, so view models left from earlier tests read what the test stored. Null
     * in the app.
     */
    @Volatile
    private var storedIn: Context? = null

    /**
     * Gives [on], reading the stored value first when it has not been read or changed yet. Reads
     * preferences and waits for the values set before it to be stored, so it is called off the main
     * thread.
     */
    fun read(context: Context): Boolean {
        if (!known) {
            synchronized(reading) {
                if (!known) {
                    val app = storedIn ?: context.applicationContext ?: context
                    val stored = store.submit(Callable { load(app) }).get()
                    onLoaded?.invoke()
                    synchronized(lock) {
                        // a change made while the stored value loaded is newer than it
                        if (!known) {
                            on = stored
                            known = true
                        }
                    }
                    notifyObservers()
                }
            }
        }
        return on
    }

    /**
     * Sets [on] to [value] at once and stores it in the background, after the values set before it.
     * Called on main by the Preferences switch.
     */
    fun change(
        context: Context,
        value: Boolean,
    ) {
        set(context, value)
    }

    /** Sets [on] to [value] as [change] does, then waits until it is stored. Called off the main thread. */
    fun write(
        context: Context,
        value: Boolean,
    ) {
        set(context, value).get()
    }

    /** Waits until every value set so far is stored; for tests. */
    internal fun awaitStored() {
        store.submit {}.get()
    }

    /**
     * Sets [on] back to its value before the first read, once the values set so far are stored, so
     * the next [read] loads the stored value again, as in a new process; for tests. With a
     * [context], every read loads what that application stored.
     */
    internal fun forget(context: Context? = null) {
        awaitStored()
        storedIn = context?.let { it.applicationContext ?: it }
        synchronized(lock) {
            on = false
            known = false
        }
        onLoaded = null
        notifyObservers()
    }

    /** Forgets as [forget] does and drops what [context]'s application stored, so a test starts from nothing. */
    internal fun clear(context: Context) {
        store
            .submit {
                prefs(context).edit().remove(KEY).commit()
                File(context.noBackupFilesDir, OLD_FILE).delete()
            }.get()
        forget(context)
    }

    private fun set(
        context: Context,
        value: Boolean,
    ): Future<*> {
        val app = context.applicationContext ?: context
        val stored =
            synchronized(lock) {
                on = value
                known = true
                store.submit { save(app, value) }
            }
        notifyObservers()
        return stored
    }

    /** The stored value, after moving a value from [OLD_FILE] into the preferences; runs on [store]. */
    private fun load(context: Context): Boolean {
        val prefs = prefs(context)
        val old = File(context.noBackupFilesDir, OLD_FILE)
        if (old.exists()) {
            if (!prefs.contains(KEY)) {
                val held = runCatching { old.readText().trim() }.getOrNull() == ON
                prefs.edit().putBoolean(KEY, held).commit()
            }
            old.delete()
        }
        return prefs.getBoolean(KEY, false)
    }

    /** Stores [value] before the next value is taken; runs on [store]. */
    private fun save(
        context: Context,
        value: Boolean,
    ) {
        if (!prefs(context).edit().putBoolean(KEY, value).commit()) Log.w(WalHost.TAG, "modern skins switch not stored")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)

    // a value set outside a composition (a background thread, startup, a click) is applied here,
    // so snapshotFlow observers see it without waiting for the next frame
    private fun notifyObservers() = Snapshot.sendApplyNotifications()

    /** The key of the stored value in [WalSettings.PREFS]. */
    internal const val KEY = "modern_skins"

    /** A file under [Context.getNoBackupFilesDir] whose value the first read moves into the preferences. */
    private const val OLD_FILE = "wal_dev"
    private const val ON = "on"
}
