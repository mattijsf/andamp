// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.ui.graphics.Color

/**
 * Parses VISCOLOR.TXT — 24 lines of `r,g,b` (commas optional, `//` comment
 * tails tolerated). Missing/invalid lines fall back per-index to [fallback].
 *
 * Index meanings: 0 background, 1 grid dots, 2..17 spectrum gradient
 * (top to bottom), 18..22 oscilloscope, 23 peak dots.
 */
object VisColorTxt {
    private val lineRegex = Regex("""^\s*(\d+)\s*,?\s*(\d+)\s*,?\s*(\d+)""")

    // Classic base skin values, the fallback when no skin provides the file.
    val DEFAULT: List<Color> =
        listOf(
            Color(0, 0, 0),
            Color(24, 33, 41),
            Color(239, 49, 16),
            Color(206, 41, 16),
            Color(214, 90, 0),
            Color(214, 102, 0),
            Color(214, 115, 0),
            Color(198, 123, 8),
            Color(222, 165, 24),
            Color(214, 181, 33),
            Color(189, 222, 41),
            Color(148, 222, 33),
            Color(41, 206, 16),
            Color(50, 190, 16),
            Color(57, 181, 16),
            Color(49, 156, 8),
            Color(41, 148, 0),
            Color(24, 132, 8),
            Color(255, 255, 255),
            Color(214, 214, 222),
            Color(181, 189, 189),
            Color(160, 170, 175),
            Color(148, 156, 165),
            Color(150, 150, 150),
        )

    fun parse(
        text: String?,
        fallback: List<Color> = DEFAULT,
    ): List<Color> {
        val parsed =
            text
                ?.lineSequence()
                ?.mapNotNull { line ->
                    lineRegex.find(line)?.let { m ->
                        val (r, g, b) = m.destructured
                        Color(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
                    }
                }?.take(24)
                ?.toList()
                .orEmpty()
        return List(24) { i -> parsed.getOrNull(i) ?: fallback.getOrElse(i) { Color.Black } }
    }
}
