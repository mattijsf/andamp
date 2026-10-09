// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences

/**
 * Where a window sits, how big it is and whether it is collapsed, in one way of holding the
 * phone. A window has one of these for upright and one for sideways, in [WindowPlacement].
 *
 * Positions are offsets from the screen center, so a remembered window lands in the same
 * place on a bigger screen. Sizes are counts (playlist segments, list rows), never pixels,
 * so they survive a skin change. A null size means "fill what is there", which is the
 * default and what dragging a window to full size gives.
 */
data class WindowArrangement(
    val x: Int? = null,
    val y: Int? = null,
    val size: Int? = null,
    /** Whether the window was collapsed to its title bar (Winamp's window shade). */
    val shaded: Boolean? = null,
    /** Steps wider than the base width; Winamp's width index. */
    val cols: Int? = null,
) {
    /** Where the window was left, or null while it has never been moved. */
    fun offset(): androidx.compose.ui.unit.IntOffset? {
        val x = x ?: return null
        val y = y ?: return null
        return androidx.compose.ui.unit
            .IntOffset(x, y)
    }
}

/**
 * One window as it is remembered: whether it is open, and where it is and how big it is
 * for each way of holding the phone.
 *
 * The two arrangements are separate because a place that suits a tall screen seldom suits a
 * wide one: turning the phone shows the arrangement made for that way round, and turning it
 * back shows the other one as it was left.
 */
data class WindowPlacement(
    /**
     * Whether the window was open when the app was last left, for the windows a listener
     * can close. Null when nothing is stored. Shared by both arrangements: closing a window
     * is a choice about the window, not about where it goes.
     */
    val open: Boolean? = null,
    val upright: WindowArrangement = WindowArrangement(),
    val sideways: WindowArrangement = WindowArrangement(),
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
}

class WindowStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether this screen turned before a window had an arrangement per way round: a tablet
     * or a Chromebook did, a phone did not. Android's line between the two: 600dp on the
     * shorter side.
     */
    private val turnedBefore = context.resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP

    fun load(): WindowLayoutMemory {
        val schema = prefs.getInt(SCHEMA, 0)
        if (schema > SCHEMA_VERSION) return WindowLayoutMemory() // written by a newer build
        val firstRun = !prefs.contains(SCHEMA)
        // A layout from before there was one arrangement per way round was made whichever
        // way the screen was held then: on a phone upright, on a tablet either way. It is
        // read for both ways round there, so nothing moves on the update.
        val oneForBoth = schema < SIDEWAYS_SCHEMA && turnedBefore
        val placements =
            WINDOWS.associateWith { id ->
                val upright = prefs.readArrangement(id)
                WindowPlacement(
                    open = prefs.readOrNull("$id.$OPEN")?.let { it != 0 },
                    upright = upright,
                    sideways = if (oneForBoth) upright else prefs.readArrangement("$id.$SIDEWAYS"),
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
                    write("$id.$OPEN", placement.open?.let { if (it) 1 else 0 })
                    writeArrangement(id, placement.upright)
                    writeArrangement("$id.$SIDEWAYS", placement.sideways)
                }
                putString(ORDER, memory.order.joinToString(","))
            }.apply()
    }

    /** The arrangement stored under [prefix]: a window's id, or its id and [SIDEWAYS]. */
    private fun SharedPreferences.readArrangement(prefix: String) =
        WindowArrangement(
            x = readOrNull("$prefix.$X"),
            y = readOrNull("$prefix.$Y"),
            size = readOrNull("$prefix.$SIZE")?.takeIf { it > 0 },
            shaded = readOrNull("$prefix.$SHADED")?.let { it != 0 },
            cols = readOrNull("$prefix.$COLS"),
        )

    private fun SharedPreferences.Editor.writeArrangement(
        prefix: String,
        arrangement: WindowArrangement,
    ) {
        write("$prefix.$X", arrangement.x)
        write("$prefix.$Y", arrangement.y)
        write("$prefix.$SIZE", arrangement.size)
        write("$prefix.$SHADED", arrangement.shaded?.let { if (it) 1 else 0 })
        write("$prefix.$COLS", arrangement.cols)
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
        private const val SCHEMA_VERSION = 2

        /** The first schema with an arrangement per way round. */
        private const val SIDEWAYS_SCHEMA = 2

        /** Android's line between a phone and a tablet: 600dp on the shorter side. */
        private const val LARGE_SCREEN_DP = 600
        private const val ORDER = "order"
        private const val X = "x"
        private const val Y = "y"
        private const val SIZE = "size"
        private const val OPEN = "open"
        private const val SHADED = "shaded"
        private const val COLS = "cols"

        /** The key segment under which a window's sideways arrangement is kept; upright has none. */
        private const val SIDEWAYS = "sideways"

        /** Stands for "absent", since `getInt` needs a default value. */
        private const val UNSET = Int.MIN_VALUE
    }
}
