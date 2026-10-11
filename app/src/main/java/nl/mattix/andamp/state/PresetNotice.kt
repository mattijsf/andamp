// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.visualizer.core.PresetGaps

/**
 * The line the plug-in window shows for a moment when a preset is not playing
 * whole: one sentence, naming at most [NAMED] of the effects left out.
 */
object PresetNotice {
    /** How many effects are named before the rest are counted. */
    const val NAMED = 3

    /** How long the line stays, in milliseconds. */
    const val SHOWN_MS = 6_000L

    /** The line for [gaps], or null when the preset plays whole. */
    fun textFor(gaps: PresetGaps): String? =
        when {
            gaps.unreadable -> "This preset could not be read"
            gaps.missing.isEmpty() -> null
            else -> "Not shown in this preset: " + listed(gaps.missing.map(::plain).distinct())
        }

    private fun listed(names: List<String>): String {
        val named = names.take(NAMED).joinToString(", ")
        val rest = names.size - NAMED
        return if (rest > 0) "$named and $rest more" else named
    }

    /**
     * A plug-in's name without the author it carries in a file: "Acko.net: Texer II" is
     * "Texer II".
     */
    private fun plain(name: String): String = name.substringAfter(": ", name).trim()
}
