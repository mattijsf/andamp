// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the listener has been shown the welcome screen, which appears once.
 *
 * An install updated from a build without the screen has no answer stored, which reads as
 * unseen, so it is shown there too.
 */
class WelcomeStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private var shown by mutableStateOf(prefs.getBoolean(KEY, false))

    /** Whether the welcome has had its turn. */
    var seen: Boolean
        get() = shown
        set(value) {
            shown = value
            prefs.edit().putBoolean(KEY, value).apply()
        }

    private companion object {
        const val PREFS = "welcome"
        const val KEY = "seen"
    }
}
