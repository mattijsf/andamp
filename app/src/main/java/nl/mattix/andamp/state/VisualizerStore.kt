// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences

/**
 * What the plug-in window was left showing: which engine, which pack per engine, shuffled
 * or not, and whether it says what a preset is missing. The pack is remembered per engine
 * because the engines cannot read each other's packs.
 */
data class VisualizerMemory(
    val plugin: VisPlugin = VisPlugin.Avs,
    val packs: Map<VisPlugin, String?> = emptyMap(),
    val shuffle: Boolean = false,
    /** Whether the window says what a preset is missing; see [PresetNotice]. */
    val notices: Boolean = true,
    /** The engines that play the listener's favorites instead of a pack. */
    val favorites: Set<VisPlugin> = emptySet(),
) {
    fun packFor(plugin: VisPlugin): String? = packs[plugin]
}

// Whether the plug-in window is open is stored with the other windows in WindowStore.

/** Remembers the visualizer's settings across launches. */
class VisualizerStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): VisualizerMemory =
        VisualizerMemory(
            plugin = VisPlugin.entries.firstOrNull { it.name == prefs.getString(PLUGIN, null) } ?: VisPlugin.Avs,
            packs =
                VisPlugin.entries.associateWith { plugin ->
                    // migration of a saved value: a single pack stored under a bare key is Milkdrop's
                    prefs.getString(PACK_PREFIX + plugin.name, null)
                        ?: prefs.getString(PACK, null).takeIf { plugin == VisPlugin.Milkdrop }
                },
            shuffle = prefs.getBoolean(SHUFFLE, false),
            notices = prefs.getBoolean(NOTICES, true),
            favorites = VisPlugin.entries.filter { prefs.getBoolean(FAVORITES_PREFIX + it.name, false) }.toSet(),
        )

    fun save(memory: VisualizerMemory) {
        val edit =
            prefs
                .edit()
                .putString(PLUGIN, memory.plugin.name)
                .putBoolean(SHUFFLE, memory.shuffle)
                .putBoolean(NOTICES, memory.notices)
        VisPlugin.entries.forEach {
            edit.putString(PACK_PREFIX + it.name, memory.packs[it])
            edit.putBoolean(FAVORITES_PREFIX + it.name, it in memory.favorites)
        }
        // drops the migrated key, so a cleared selection does not come back from it
        edit.remove(PACK)
        edit.apply()
    }

    private companion object {
        const val PREFS = "visualizer"
        const val PLUGIN = "plugin"

        /** The key of the single-pack format, read only as a fallback. */
        const val PACK = "pack"
        const val PACK_PREFIX = "pack."
        const val SHUFFLE = "shuffle"
        const val NOTICES = "notices"
        const val FAVORITES_PREFIX = "favorites."
    }
}
