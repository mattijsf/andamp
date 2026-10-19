// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which source the library window is showing, remembered between launches. It is separate
 * from what is playing.
 *
 * Stored as the source's id, which [LibrarySources] matches against the sources this install
 * has; an unknown id matches nothing and the window shows the phone. [ALL] is every source
 * together, and null is no pick at all, which [LibrarySources] reads as every source when
 * there is more than one. Held as snapshot state too, so the menu's tick moves when a source
 * is picked.
 */
class LibrarySourceStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    init {
        // every source together came after the single pick, and is where every install
        // starts: a pick from before it is let go once
        if (!prefs.getBoolean(ALL_OFFERED, false)) {
            prefs
                .edit()
                .remove(KEY)
                .putBoolean(ALL_OFFERED, true)
                .apply()
        }
    }

    private var current by mutableStateOf(prefs.getString(KEY, null))

    var id: String?
        get() = current
        set(value) {
            current = value
            prefs.edit().putString(KEY, value).apply()
        }

    companion object {
        /** Every source together. */
        const val ALL = "ALL"

        private const val PREFS = "library"
        private const val KEY = "source"
        private const val ALL_OFFERED = "all"
    }
}
