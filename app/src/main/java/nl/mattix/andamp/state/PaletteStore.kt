// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether AndAmp Dark and Light take their colors from the wallpaper.
 *
 * When on, the two live skins are rebuilt from the phone's palette (see
 * [nl.mattix.andamp.skin.LiveSkins]). Off uses the shipped files.
 *
 * It has its own preferences file because the home screen widget reads it from a broadcast
 * with no player running.
 */
class PaletteStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Snapshot state, so the switch and the skins recompose on a change. */
    private var current by mutableStateOf(prefs.getBoolean(KEY, DEFAULT))

    /** Whether the live skins follow the wallpaper; false uses the shipped files. */
    var enabled: Boolean
        get() = current
        set(value) {
            current = value
            prefs.edit().putBoolean(KEY, value).apply()
        }

    companion object {
        private const val PREFS = "palette"
        private const val KEY = "wallpaper"

        /** On, matching the rest of the phone. */
        const val DEFAULT = true
    }
}
