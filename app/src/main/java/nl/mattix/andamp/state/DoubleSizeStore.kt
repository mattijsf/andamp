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
 * stack as wide as the screen. The stack keeps a height of its own for the plug-in window,
 * so the size that window floats at is not changed by it. The floating player always
 * floats: there the setting can be switched, and it shows once the app has the player again.
 *
 * A fresh install starts with it on, and an install that was already in use keeps the player
 * as it was. Which of the two this is, is asked once, when nothing is stored yet, and the
 * answer is stored; see [neverUpdated].
 */
class DoubleSizeStore(
    private val prefs: SharedPreferences,
    /** Receives the setting when it is read and on every change; the clutter bar's D is lit from it. */
    private val mirror: (Boolean) -> Unit = {},
    freshInstall: () -> Boolean,
) {
    constructor(context: Context, mirror: (Boolean) -> Unit = {}, freshInstall: () -> Boolean) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), mirror, freshInstall)

    /** Snapshot state, so the player lays itself out again on a change. */
    private var current by mutableStateOf(
        if (prefs.contains(ON)) {
            prefs.getBoolean(ON, false)
        } else {
            freshInstall().also { prefs.edit().putBoolean(ON, it).apply() }
        },
    )

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

    private companion object {
        const val PREFS = "doublesize"
        const val ON = "on"
        const val VIS_STEPS = "vis_steps"
    }
}

/**
 * Whether this install has never been updated: the app was put on the device as the build
 * that is running.
 *
 * The app's own files cannot answer this. Android restores a backup of them when the app is
 * installed again, so a new install can start with the window layout and the settings of an
 * older one.
 */
fun Context.neverUpdated(): Boolean {
    val installed = packageManager.getPackageInfo(packageName, 0)
    return installed.firstInstallTime == installed.lastUpdateTime
}
