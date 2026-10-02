// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether a window may be collapsed to its title bar at all.
 *
 * Winamp's window shade is a small button and a double tap on the title bar, which is also
 * the drag handle, so on a phone it is easy to trigger by accident. Off locks every window
 * open.
 *
 * It has its own preferences file because it is read before a window is drawn and written
 * from a settings screen.
 */
class ShadeStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Snapshot state, so the switch and the windows recompose on a change. */
    private var current by mutableStateOf(prefs.getBoolean(KEY, DEFAULT))

    /** Whether collapsing is offered; false locks every window open. */
    var enabled: Boolean
        get() = current
        set(value) {
            current = value
            prefs.edit().putBoolean(KEY, value).apply()
        }

    companion object {
        private const val PREFS = "shade"
        private const val KEY = "enabled"

        /** On, as in Winamp. */
        const val DEFAULT = true
    }
}
