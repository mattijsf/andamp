// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

import android.content.Context
import android.content.SharedPreferences

/** Whether the listener asked for the floating player, across restarts. */
class OverlayStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var wanted: Boolean
        get() = prefs.getBoolean(WANTED, false)
        set(value) {
            prefs.edit().putBoolean(WANTED, value).apply()
        }

    private companion object {
        const val PREFS = "overlay"
        const val WANTED = "wanted"
    }
}
