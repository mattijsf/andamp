// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences

/** What a listener left the transport set to. */
data class TransportState(
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
)

/**
 * Remembers shuffle and repeat.
 *
 * Volume is not stored here. The position within a track is not stored either: like
 * Winamp, the player comes back stopped at the start of the track it was on
 * ([PlaylistStore] keeps the row).
 */
class TransportStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load() =
        TransportState(
            shuffle = prefs.getBoolean(SHUFFLE, false),
            repeat = prefs.getBoolean(REPEAT, false),
        )

    fun saveSettings(state: TransportState) {
        prefs
            .edit()
            .putBoolean(SHUFFLE, state.shuffle)
            .putBoolean(REPEAT, state.repeat)
            .apply()
    }

    private companion object {
        const val PREFS = "transport"
        const val SHUFFLE = "shuffle"
        const val REPEAT = "repeat"
    }
}
