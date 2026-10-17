// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.core

/**
 * What an engine could not show of the preset it was asked to play.
 *
 * A preset that names an effect the engine does not have still plays, without
 * that effect. A preset the engine cannot read at all is replaced by the
 * engine's own.
 */
data class PresetGaps(
    /** The preset could not be read, and something else is showing in its place. */
    val unreadable: Boolean = false,
    /** The effects left out, by the name the preset gives them, once each. */
    val missing: List<String> = emptyList(),
    /** Why it could not be read, in the engine's words, when the engine gave one. */
    val reason: String? = null,
) {
    val none get() = !unreadable && missing.isEmpty()

    companion object {
        val NONE = PresetGaps()
    }
}
