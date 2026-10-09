// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * One of the four ways the windows are laid out: floating or held in Double Size's locked
 * layout, each with the phone upright or sideways.
 *
 * Each is a workspace of its own. Which windows are open and which are collapsed, where the
 * floating ones sit and how big they are, how tall the plug-in window is in the locked layout,
 * and which window is in front, is kept per workspace: the sizes and shapes differ so much that
 * one setup seldom suits another, and switching shows a workspace as it was left.
 */
enum class Workspace(
    /** Whether the layout holds the windows in place: Double Size. */
    val locked: Boolean,
    /** Whether the phone is held sideways. */
    val sideways: Boolean,
    /** What the store names it by; the floating upright workspace, the first there was, has no name. */
    internal val key: String,
) {
    FLOATING(locked = false, sideways = false, key = ""),
    FLOATING_SIDEWAYS(locked = false, sideways = true, key = "sideways"),
    LOCKED(locked = true, sideways = false, key = "double"),
    LOCKED_SIDEWAYS(locked = true, sideways = true, key = "double.sideways"),
    ;

    companion object {
        fun of(
            locked: Boolean,
            sideways: Boolean,
        ): Workspace = entries.first { it.locked == locked && it.sideways == sideways }
    }
}
