// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import java.util.Locale

/**
 * Finding presets in a list by what the list shows of them: the title and the folders under it.
 * Every word typed has to be there, in any order and any case, so "glow mirror" finds a preset
 * in Dancer / Glowsticks Mirror.
 */
object PresetSearch {
    /** The words of [query], in lower case. */
    fun words(query: String): List<String> = query.lowercase(Locale.ROOT).split(SPACE).filter { it.isNotEmpty() }

    /** What a preset is searched by: its [title] and its [place], in lower case. */
    fun text(
        title: String,
        place: String?,
    ): String = listOfNotNull(title, place).joinToString(" ").lowercase(Locale.ROOT)

    fun matches(
        text: String,
        words: List<String>,
    ): Boolean = words.all { it in text }

    private val SPACE = Regex("""\s+""")
}
