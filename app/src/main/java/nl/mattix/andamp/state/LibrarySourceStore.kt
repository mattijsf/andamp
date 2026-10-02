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
 * has; an unknown id matches nothing and the window shows the phone. Held as snapshot state
 * too, so the menu's tick moves when a source is picked.
 */
class LibrarySourceStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private var current by mutableStateOf(prefs.getString(KEY, null) ?: MusicSource.LOCAL.id)

    var id: String
        get() = current
        set(value) {
            current = value
            prefs.edit().putString(KEY, value).apply()
        }

    private companion object {
        const val PREFS = "library"
        const val KEY = "source"
    }
}
