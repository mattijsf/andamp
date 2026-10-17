// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.R
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
    fun textFor(
        gaps: PresetGaps,
        words: Words,
    ): String? =
        when {
            gaps.unreadable -> words.get(R.string.preset_unreadable)
            gaps.missing.isEmpty() -> null
            else -> words.get(R.string.preset_not_shown, listed(gaps.missing.map(::plain).distinct(), words))
        }

    /**
     * The longer line the preset selector shows under a preset: [textFor], with the engine's
     * reason when it could not read the preset and gave one.
     */
    fun detailFor(
        gaps: PresetGaps,
        words: Words,
    ): String? =
        gaps.reason
            ?.takeIf { gaps.unreadable && it.isNotBlank() }
            ?.let { words.get(R.string.preset_unreadable_because, it.trim()) }
            ?: textFor(gaps, words)

    private fun listed(
        names: List<String>,
        words: Words,
    ): String {
        val named = names.take(NAMED).joinToString(", ")
        val rest = names.size - NAMED
        return if (rest > 0) words.get(R.string.preset_and_more, named, rest) else named
    }

    /**
     * A plug-in's name without the author it carries in a file: "Acko.net: Texer II" is
     * "Texer II".
     */
    private fun plain(name: String): String = name.substringAfter(": ", name).trim()
}
