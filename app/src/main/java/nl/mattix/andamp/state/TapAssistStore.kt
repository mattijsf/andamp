// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether holding a small control opens the magnified view over it.
 *
 * Winamp's controls were drawn for a mouse and a thumb covers several at once. Holding one
 * opens a magnified strip of its neighbors, so the right one can be picked before the
 * finger lifts. Off, a control answers a plain tap only.
 *
 * It has its own preferences file because it is read before a window is drawn and written
 * from a settings screen.
 */
class TapAssistStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Snapshot state, so the switch and the windows recompose on a change. */
    private var current by mutableStateOf(prefs.getBoolean(KEY, DEFAULT))

    /** Whether a hold opens the magnifier; false leaves every control tap-only. */
    var enabled: Boolean
        get() = current
        set(value) {
            current = value
            prefs.edit().putBoolean(KEY, value).apply()
        }

    companion object {
        private const val PREFS = "tapassist"
        private const val KEY = "enabled"

        const val DEFAULT = true
    }
}
