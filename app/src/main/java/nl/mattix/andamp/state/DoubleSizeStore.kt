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
 * Off, the windows float at the largest whole scale that fits. On, they are held in one
 * stack as large as the screen takes: as wide as it, or in its middle where the height
 * decides. The stack keeps a height of its own for the plug-in window
 * and its own collapsed windows, so the floating layout is not changed by it.
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

    private var steps by mutableStateOf(if (prefs.contains(VIS_STEPS)) prefs.getInt(VIS_STEPS, 0) else null)

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

    /**
     * How tall the plug-in window's visual is while the player fills the screen, in resize
     * steps; null until its grip has been dragged there.
     */
    var visSteps: Int?
        get() = steps
        set(value) {
            steps = value
            prefs.edit().apply { if (value == null) remove(VIS_STEPS) else putInt(VIS_STEPS, value) }.apply()
        }

    /**
     * The windows collapsed to their title bars while the player fills the screen, by their
     * ids. The stack has its own, so the floating layout's are not changed by it.
     */
    var shaded: Set<String>
        get() = prefs.getStringSet(SHADED, emptySet()).orEmpty().toSet()
        set(value) {
            prefs.edit().putStringSet(SHADED, value).apply()
        }

    private companion object {
        const val PREFS = "doublesize"
        const val ON = "on"
        const val VIS_STEPS = "vis_steps"
        const val SHADED = "shaded"
    }
}
