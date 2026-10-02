// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences

/**
 * Where a window sits, how big it is, and how it was left.
 *
 * Positions are offsets from the screen center, so a remembered window lands in the same
 * place on a bigger screen. Sizes are counts (playlist segments, list rows), never pixels,
 * so they survive a skin change. A null size means "fill what is there", which is the
 * default and what dragging a window to full size gives.
 */
data class WindowPlacement(
    val x: Int? = null,
    val y: Int? = null,
    val size: Int? = null,
    /**
     * Whether the window was open when the app was last left, for the windows a listener
     * can close. Null when nothing is stored.
     */
    val open: Boolean? = null,
    /** Whether the window was collapsed to its title bar (Winamp's window shade). */
    val shaded: Boolean? = null,
    /** Steps wider than the base width; Winamp's width index. */
    val cols: Int? = null,
)

/** Every window's placement, plus the stack they are in. */
data class WindowLayoutMemory(
    val placements: Map<String, WindowPlacement> = emptyMap(),
    /** Floating windows, bottom to top; unknown ids are ignored on load. */
    val order: List<String> = emptyList(),
    /**
     * Whether nothing has ever been written here: a fresh install.
     *
     * This differs from every placement being null, because dragging a window back to
     * full size also stores a null size. Only a fresh install starts the playlist at
     * [WindowStore.PLAYLIST_START_SEGMENTS].
     */
    val firstRun: Boolean = false,
) {
    fun placementOf(id: String) = placements[id] ?: WindowPlacement()

    /** Where a window was left, or null while it has never been moved. */
    fun offsetOf(id: String): androidx.compose.ui.unit.IntOffset? {
        val placement = placementOf(id)
        val x = placement.x ?: return null
        val y = placement.y ?: return null
        return androidx.compose.ui.unit
            .IntOffset(x, y)
    }
}

class WindowStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): WindowLayoutMemory {
        if (prefs.getInt(SCHEMA, 0) > SCHEMA_VERSION) return WindowLayoutMemory() // written by a newer build
        val firstRun = !prefs.contains(SCHEMA)
        val placements =
            WINDOWS.associateWith { id ->
                WindowPlacement(
                    x = prefs.readOrNull("$id.$X"),
                    y = prefs.readOrNull("$id.$Y"),
                    size = prefs.readOrNull("$id.$SIZE")?.takeIf { it > 0 },
                    open = prefs.readOrNull("$id.$OPEN")?.let { it != 0 },
                    shaded = prefs.readOrNull("$id.$SHADED")?.let { it != 0 },
                    cols = prefs.readOrNull("$id.$COLS"),
                )
            }
        val order =
            prefs
                .getString(ORDER, null)
                ?.split(',')
                ?.filter { it in WINDOWS }
                .orEmpty()
        return WindowLayoutMemory(placements, order, firstRun)
    }

    fun save(memory: WindowLayoutMemory) {
        prefs
            .edit()
            .apply {
                putInt(SCHEMA, SCHEMA_VERSION)
                WINDOWS.forEach { id ->
                    val placement = memory.placementOf(id)
                    write("$id.$X", placement.x)
                    write("$id.$Y", placement.y)
                    write("$id.$SIZE", placement.size)
                    write("$id.$OPEN", placement.open?.let { if (it) 1 else 0 })
                    write("$id.$SHADED", placement.shaded?.let { if (it) 1 else 0 })
                    write("$id.$COLS", placement.cols)
                }
                putString(ORDER, memory.order.joinToString(","))
            }.apply()
    }

    private fun SharedPreferences.readOrNull(key: String): Int? = getInt(key, UNSET).takeIf { it != UNSET }

    private fun SharedPreferences.Editor.write(
        key: String,
        value: Int?,
    ) {
        if (value == null) remove(key) else putInt(key, value)
    }

    companion object {
        /** The ids of the windows a listener can move. */
        const val MAIN = "main"
        const val EQ = "eq"
        const val MILKDROP = "milkdrop"
        const val PLAYLIST = "pl"
        const val LIBRARY = "library"
        const val SKINS = "skins"
        val WINDOWS = listOf(MAIN, EQ, MILKDROP, PLAYLIST, LIBRARY, SKINS)

        /**
         * How tall the playlist is on a fresh install, in playlist segments. A null size
         * would make it fill the rest of the screen; the listener can drag it to that, and
         * the choice is then stored.
         */
        const val PLAYLIST_START_SEGMENTS = 6

        private const val PREFS = "windows"
        private const val SCHEMA = "schema"
        private const val SCHEMA_VERSION = 1
        private const val ORDER = "order"
        private const val X = "x"
        private const val Y = "y"
        private const val SIZE = "size"
        private const val OPEN = "open"
        private const val SHADED = "shaded"
        private const val COLS = "cols"

        /** Stands for "absent", since `getInt` needs a default value. */
        private const val UNSET = Int.MIN_VALUE
    }
}
