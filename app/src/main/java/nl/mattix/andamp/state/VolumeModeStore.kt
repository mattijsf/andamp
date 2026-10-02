// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.mattix.andamp.core.model.VolumeMode

/**
 * Whose volume the slider moves, remembered between launches. It has its own preferences
 * file, so the backend can be told before anything plays.
 */
class VolumeModeStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Snapshot state, so a Compose switch reading [mode] redraws when it changes. */
    private var current by mutableStateOf(stored())

    var mode: VolumeMode
        get() = current
        set(value) {
            current = value
            prefs.edit().putString(KEY, value.name).apply()
        }

    // an unknown stored name (a downgrade, a corrupt file) falls back to the default
    private fun stored(): VolumeMode =
        runCatching { VolumeMode.valueOf(prefs.getString(KEY, null) ?: DEFAULT.name) }
            .getOrDefault(DEFAULT)

    companion object {
        private const val PREFS = "volume"
        private const val KEY = "mode"

        /** The device's volume: the volume keys and the slider agree. */
        val DEFAULT = VolumeMode.DEVICE
    }
}
