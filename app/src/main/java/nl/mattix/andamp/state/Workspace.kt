// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * One of the two ways the windows are laid out: with the phone upright or sideways.
 *
 * Each is a workspace apart. Which windows are open and which are collapsed, where they sit
 * and how big they are, and which window is in front, is kept per workspace: the sizes and
 * shapes differ so much that one setup seldom suits the other, and turning the phone shows a
 * workspace as it was left.
 */
enum class Workspace(
    /** Whether the phone is held sideways. */
    val sideways: Boolean,
    /** What the store names it by; the upright workspace, the first there was, has no name. */
    internal val key: String,
) {
    FLOATING(sideways = false, key = ""),
    FLOATING_SIDEWAYS(sideways = true, key = "sideways"),
    ;

    companion object {
        fun of(sideways: Boolean): Workspace = if (sideways) FLOATING_SIDEWAYS else FLOATING
    }
}
