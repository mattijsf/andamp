// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the app remembers about tips: whether one was ever given, and whether the store may hold
 * one that still has to be settled. See [TipJar].
 */
class TipLedger(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Snapshot state, so the support page recomposes when the first tip arrives. */
    private var thanked by mutableStateOf(prefs.getBoolean(GIVEN, false))

    /** Whether this listener ever gave a tip. Only the page's wording depends on it. */
    var given: Boolean
        get() = thanked
        set(value) {
            thanked = value
            prefs.edit().putBoolean(GIVEN, value).apply()
        }

    /** Whether a tip was started and not seen settled, so the store is asked again at the next start. */
    var owed: Boolean
        get() = prefs.getBoolean(OWED, false)
        set(value) = prefs.edit().putBoolean(OWED, value).apply()

    private companion object {
        const val PREFS = "tips"
        const val GIVEN = "given"
        const val OWED = "owed"
    }
}
