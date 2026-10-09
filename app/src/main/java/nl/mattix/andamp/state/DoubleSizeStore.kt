// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Winamp's Double Size, the D in the clutter bar: whether the player fills the screen.
 *
 * Off, the windows float at the largest whole scale that fits. On, the layout holds them,
 * as large as the screen takes. Each is a [Workspace] of its own, with its own open and
 * collapsed windows and, held in place, its own height for the plug-in window; see
 * [WindowStore].
 *
 * It does not go together with Always On Top, where the player floats over other apps: only
 * one of the two is on at a time. Whoever builds this store is told when it is switched on
 * ([onSwitchedOn]) and switches the other off.
 *
 * It is off until the listener switches it on.
 */
class DoubleSizeStore(
    private val prefs: SharedPreferences,
    /** Receives the setting when it is read and on every change; the clutter bar's D is lit from it. */
    private val mirror: (Boolean) -> Unit = {},
    /** Called when the setting is switched on, and not when it is read as on. */
    private val onSwitchedOn: () -> Unit = {},
) {
    constructor(
        context: Context,
        mirror: (Boolean) -> Unit = {},
        onSwitchedOn: () -> Unit = {},
    ) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), mirror, onSwitchedOn)

    /** Snapshot state, so the player lays itself out again on a change. */
    private var current by mutableStateOf(prefs.getBoolean(ON, false))

    init {
        mirror(current)
    }

    /** Whether the player fills the screen. */
    var on: Boolean
        get() = current
        set(value) {
            current = value
            prefs.edit().putBoolean(ON, value).apply()
            mirror(value)
            if (value) onSwitchedOn()
        }

    fun toggle() {
        on = !on
    }

    private companion object {
        const val PREFS = "doublesize"
        const val ON = "on"
    }
}
