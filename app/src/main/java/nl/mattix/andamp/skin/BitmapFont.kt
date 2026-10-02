// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import java.text.Normalizer

/**
 * The TEXT.BMP bitmap font: 5x6 pixel glyphs in a 31-column grid.
 * Lookup table transcribed from webamp's FONT_LOOKUP (skinSprites.ts).
 */
object BitmapFont {
    const val CHAR_W = 5
    const val CHAR_H = 6

    // char -> (row, col) in TEXT.BMP
    private val lookup: Map<Char, Pair<Int, Int>> =
        buildMap {
            "abcdefghijklmnopqrstuvwxyz".forEachIndexed { i, c -> put(c, 0 to i) }
            put('"', 0 to 26)
            put('@', 0 to 27)
            put(' ', 0 to 30)
            "0123456789".forEachIndexed { i, c -> put(c, 1 to i) }
            put('…', 1 to 10) // ellipsis
            put('.', 1 to 11)
            put(':', 1 to 12)
            put('(', 1 to 13)
            put(')', 1 to 14)
            put('-', 1 to 15)
            put('\'', 1 to 16)
            put('!', 1 to 17)
            put('_', 1 to 18)
            put('+', 1 to 19)
            put('\\', 1 to 20)
            put('/', 1 to 21)
            put('[', 1 to 22)
            put(']', 1 to 23)
            put('^', 1 to 24)
            put('&', 1 to 25)
            put('%', 1 to 26)
            put(',', 1 to 27)
            put('=', 1 to 28)
            put('$', 1 to 29)
            put('#', 1 to 30)
            put('Å', 2 to 0)
            put('Ö', 2 to 1)
            put('Ä', 2 to 2)
            put('?', 2 to 3)
            put('*', 2 to 4)
            put('<', 1 to 22)
            put('>', 1 to 23)
            put('{', 1 to 22)
            put('}', 1 to 23)
        }

    /** Source rect in TEXT.BMP for one (already normalized) character. */
    fun sprite(c: Char): Sprite {
        val (row, col) = lookup[c] ?: (0 to 30) // unknown -> space
        return Sprite(col * CHAR_W, row * CHAR_H, CHAR_W, CHAR_H)
    }

    /**
     * Winamp folds text before rendering: lowercase, accents stripped
     * (except the Å/Ö/Ä glyphs which exist in the sheet).
     */
    fun normalize(text: String): String =
        buildString(text.length) {
            for (c in text.lowercase()) {
                when (c) {
                    'å', 'Å' -> {
                        append('Å')
                    }

                    'ö', 'Ö' -> {
                        append('Ö')
                    }

                    'ä', 'Ä' -> {
                        append('Ä')
                    }

                    else -> {
                        if (c in lookup) {
                            append(c)
                        } else {
                            val folded =
                                Normalizer
                                    .normalize(c.toString(), Normalizer.Form.NFD)
                                    .firstOrNull { it in lookup }
                            append(folded ?: ' ')
                        }
                    }
                }
            }
        }
}
