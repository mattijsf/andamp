// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences

/**
 * One window as it is set up in one [Workspace]: whether it is open and collapsed, where it
 * sits and how big it is.
 *
 * Positions are offsets from the screen center, so a remembered window lands in the same
 * place on a bigger screen. Sizes are counts (playlist segments, list rows, resize steps),
 * never pixels, so they survive a skin change. A null size means "fill what is there", which
 * is the default and what dragging a window to full size gives. In a locked workspace the
 * layout places and sizes the windows, and only [open], [shaded] and the plug-in window's
 * [size], its height in steps, are its own.
 */
data class WindowArrangement(
    val x: Int? = null,
    val y: Int? = null,
    val size: Int? = null,
    /** Whether the window was collapsed to its title bar (Winamp's window shade). */
    val shaded: Boolean? = null,
    /** Steps wider than the base width; Winamp's width index. */
    val cols: Int? = null,
    /**
     * Whether the window was open when the app was last left, for the windows a listener
     * can close. Null when nothing is stored.
     */
    val open: Boolean? = null,
) {
    /** Where the window was left, or null while it has never been moved. */
    fun offset(): androidx.compose.ui.unit.IntOffset? {
        val x = x ?: return null
        val y = y ?: return null
        return androidx.compose.ui.unit
            .IntOffset(x, y)
    }
}

/** One window as it is remembered: an arrangement per [Workspace]. */
data class WindowPlacement(
    val arrangements: Map<Workspace, WindowArrangement> = emptyMap(),
) {
    fun of(workspace: Workspace) = arrangements[workspace] ?: WindowArrangement()
}

/** Every window's placement, plus each workspace's stack. */
data class WindowLayoutMemory(
    val placements: Map<String, WindowPlacement> = emptyMap(),
    /** Each workspace's windows, bottom to top; unknown ids are ignored on load. */
    val orders: Map<Workspace, List<String>> = emptyMap(),
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

    fun orderOf(workspace: Workspace) = orders[workspace].orEmpty()
}

class WindowStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where Double Size kept the locked layout's collapsed windows and visual height before [WORKSPACE_SCHEMA]. */
    private val oldStack: SharedPreferences = context.getSharedPreferences(OLD_STACK_PREFS, Context.MODE_PRIVATE)

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
        val placements =
            WINDOWS.associateWith { id ->
                if (schema < WORKSPACE_SCHEMA) placementBeforeWorkspaces(id, schema) else placementOf(id)
            }
        val orders =
            Workspace.entries.associateWith { workspace ->
                // before there were workspaces, one order served every layout
                readOrder(if (schema < WORKSPACE_SCHEMA) Workspace.FLOATING else workspace)
            }
        return WindowLayoutMemory(placements, orders, firstRun)
    }

    fun save(memory: WindowLayoutMemory) {
        prefs
            .edit()
            .apply {
                putInt(SCHEMA, SCHEMA_VERSION)
                WINDOWS.forEach { id ->
                    val placement = memory.placementOf(id)
                    Workspace.entries.forEach { workspace -> writeArrangement(workspace.prefix(id), placement.of(workspace)) }
                }
                Workspace.entries.forEach { workspace ->
                    putString(workspace.orderKey(), memory.orderOf(workspace).joinToString(","))
                }
            }.apply()
    }

    private fun placementOf(id: String) =
        WindowPlacement(Workspace.entries.associateWith { workspace -> prefs.readArrangement(workspace.prefix(id)) })

    /**
     * A layout from before there was an arrangement per workspace. The floating layout had
     * one arrangement, and from [SIDEWAYS_SCHEMA] one for sideways as well; whether a window
     * was open was shared by every layout; and Double Size kept the locked layout's collapsed
     * windows and its visual's height in settings of its own. Read so that nothing moves on
     * the update.
     */
    private fun placementBeforeWorkspaces(
        id: String,
        schema: Int,
    ): WindowPlacement {
        val open = prefs.readOrNull("$id.$OPEN")?.let { it != 0 }
        val upright = prefs.readArrangement(id).copy(open = open)
        val sideways =
            if (schema < SIDEWAYS_SCHEMA && turnedBefore) {
                // a tablet turned before, so what it had serves either way round
                upright
            } else {
                prefs.readArrangement(Workspace.FLOATING_SIDEWAYS.prefix(id)).copy(open = open)
            }
        val locked =
            WindowArrangement(
                size = oldStack.readOrNull(OLD_STACK_VIS_STEPS).takeIf { id == MILKDROP },
                shaded = id in oldStack.getStringSet(OLD_STACK_SHADED, emptySet()).orEmpty(),
                open = open,
            )
        return WindowPlacement(
            mapOf(
                Workspace.FLOATING to upright,
                Workspace.FLOATING_SIDEWAYS to sideways,
                Workspace.LOCKED to locked,
                Workspace.LOCKED_SIDEWAYS to locked,
            ),
        )
    }

    private fun readOrder(workspace: Workspace): List<String> =
        prefs
            .getString(workspace.orderKey(), null)
            ?.split(',')
            ?.filter { it in WINDOWS }
            .orEmpty()

    /** The key segment a window's arrangement in [this] workspace is kept under. */
    private fun Workspace.prefix(id: String) = if (key.isEmpty()) id else "$id.$key"

    private fun Workspace.orderKey() = if (key.isEmpty()) ORDER else "$key.$ORDER"

    private fun SharedPreferences.readArrangement(prefix: String) =
        WindowArrangement(
            x = readOrNull("$prefix.$X"),
            y = readOrNull("$prefix.$Y"),
            size = readOrNull("$prefix.$SIZE")?.takeIf { it > 0 },
            shaded = readOrNull("$prefix.$SHADED")?.let { it != 0 },
            cols = readOrNull("$prefix.$COLS"),
            open = readOrNull("$prefix.$OPEN")?.let { it != 0 },
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
        write("$prefix.$OPEN", arrangement.open?.let { if (it) 1 else 0 })
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
        private const val SCHEMA_VERSION = 3

        /** The first schema with an arrangement for the floating layout held sideways. */
        private const val SIDEWAYS_SCHEMA = 2

        /** The first schema with an arrangement per [Workspace], open windows included. */
        private const val WORKSPACE_SCHEMA = 3

        private const val ORDER = "order"
        private const val X = "x"
        private const val Y = "y"
        private const val SIZE = "size"
        private const val OPEN = "open"
        private const val SHADED = "shaded"
        private const val COLS = "cols"

        /** Double Size's own settings, where it kept the locked layout's state before [WORKSPACE_SCHEMA]. */
        private const val OLD_STACK_PREFS = "doublesize"
        private const val OLD_STACK_SHADED = "shaded"
        private const val OLD_STACK_VIS_STEPS = "vis_steps"

        /** Android's line between a phone and a tablet: 600dp on the shorter side. */
        private const val LARGE_SCREEN_DP = 600

        /** Stands for "absent", since `getInt` needs a default value. */
        private const val UNSET = Int.MIN_VALUE
    }
}
