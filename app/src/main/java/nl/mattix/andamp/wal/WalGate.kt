// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import nl.mattix.andamp.BuildConfig
import java.io.File

/**
 * Whether modern skins may be worn. While it is off, nothing reaches the modern-skin engine
 * through the player.
 *
 * The value lives in the file [FILE] under [Context.getNoBackupFilesDir], holding "on" or "off".
 * With no file, debug builds read on and release builds read off. No screen writes the file; only
 * [WalDevSwitch] does, over adb. It is not a preference, because preferences go into Auto Backup
 * and would carry the value to another install.
 *
 * The application reads the file once at startup, on a background thread. [read] may be called
 * again from anywhere off the main thread: after the first time it gives the value at once. Code
 * that decides at startup whether a modern skin goes on calls [read], which waits for the file;
 * [on] reads off until the file has been read.
 *
 * [on] is snapshot state, so a composable that reads it follows a switch at once. Code outside a
 * composition follows it with `snapshotFlow { WalGate.on }`; that is how SkinOps is to put a modern
 * skin on or take it off when the switch changes, while the process runs.
 */
object WalGate {
    /** True while modern skins may be worn. False until the file has been read. */
    var on by mutableStateOf(false)
        private set

    /** Whether [on] holds the file's value yet. */
    private var known = false

    /**
     * Gives [on], reading the file first when it has not been read yet. Reads a file, so it is
     * called off the main thread. [debug] gives the value with no file: on in debug builds.
     */
    @Synchronized
    fun read(
        context: Context,
        debug: Boolean = BuildConfig.DEBUG,
    ): Boolean {
        if (!known) {
            val stored = runCatching { file(context).readText().trim() }.getOrNull()
            settle(
                when (stored) {
                    ON -> true
                    OFF -> false
                    else -> debug
                },
            )
        }
        return on
    }

    /** Writes [value] to the file and sets [on] to it. Writes a file, so it is called off the main thread. */
    @Synchronized
    fun write(
        context: Context,
        value: Boolean,
    ) {
        file(context).writeText(if (value) ON else OFF)
        settle(value)
    }

    /** The file that holds the value. */
    internal fun file(context: Context) = File(context.noBackupFilesDir, FILE)

    /** Sets [on] back to its value before the first read, so each test starts from nothing. */
    @Synchronized
    internal fun forget() {
        on = false
        known = false
        Snapshot.sendApplyNotifications()
    }

    private fun settle(value: Boolean) {
        on = value
        known = true
        // this runs outside a composition (a receiver's thread, startup), where nothing applies
        // snapshot writes, so observers are notified here
        Snapshot.sendApplyNotifications()
    }

    private const val FILE = "wal_dev"
    private const val ON = "on"
    private const val OFF = "off"
}
