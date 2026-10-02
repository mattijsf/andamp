// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences

/** What the in-player visualizer was set to. */
data class Visuals(
    val mode: VisMode = VisMode.Analyzer,
)

/**
 * Remembers the in-player visualizer mode ([VisMode]).
 *
 * Where the window sits belongs to [WindowStore], and what the plug-in window shows belongs
 * to [VisualizerStore]. Fullscreen is not stored, so the app never opens into it.
 */
class VisualsStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Visuals {
        val stored = prefs.getString(MODE, null)
        return Visuals(
            // a stored name this build does not have falls back to the analyzer
            mode = VisMode.entries.firstOrNull { it.name == stored } ?: VisMode.Analyzer,
        )
    }

    fun save(visuals: Visuals) {
        prefs
            .edit()
            .putString(MODE, visuals.mode.name)
            .apply()
    }

    private companion object {
        const val PREFS = "visuals"
        const val MODE = "mode"
    }
}
