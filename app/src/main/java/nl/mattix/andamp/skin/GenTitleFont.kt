// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

/**
 * The variable-width title font of Winamp's generic window frame.
 *
 * GEN.BMP carries A-Z twice as 7px-tall strips — the lit row for a focused
 * window and the dim row for an unfocused one — with letters separated by a
 * single column of the row's background color. Widths differ per letter and
 * per skin, so they can't be tabulated: Winamp (and webamp's skin parser)
 * scan the row at load time, which is what [scan] does.
 */
class GenTitleFont(
    private val active: Map<Char, Sprite>,
    private val inactive: Map<Char, Sprite>,
) {
    fun sprite(
        letter: Char,
        focused: Boolean,
    ): Sprite? = (if (focused) active else inactive)[letter.uppercaseChar()]

    /** Rendered width of [text] in virtual px, spaces included. */
    fun width(
        text: String,
        focused: Boolean,
    ): Int =
        text.sumOf { char ->
            when {
                char == ' ' -> SPACE_W
                else -> sprite(char, focused)?.w ?: 0
            }
        }

    companion object {
        /** Width of a space; webamp's .gen-text-space. */
        const val SPACE_W = 5

        /** Letter strip rows in GEN.BMP: lit at y=88, dim at y=96. */
        private const val ACTIVE_ROW = 88
        private const val INACTIVE_ROW = 96
        private const val LETTER_H = 7
        private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /**
         * Scans both letter rows out of a GEN.BMP pixel reader. Returns null
         * when the sheet is too small to hold them (a skin with a stub GEN.BMP),
         * so callers can fall back instead of drawing garbage.
         */
        fun scan(
            width: Int,
            height: Int,
            pixelAt: (x: Int, y: Int) -> Int,
        ): GenTitleFont? {
            if (width < 2 || height < INACTIVE_ROW + LETTER_H) return null
            val active = scanRow(ACTIVE_ROW, width, pixelAt) ?: return null
            val inactive = scanRow(INACTIVE_ROW, width, pixelAt) ?: return null
            return GenTitleFont(active, inactive)
        }

        private fun scanRow(
            y: Int,
            width: Int,
            pixelAt: (x: Int, y: Int) -> Int,
        ): Map<Char, Sprite>? {
            // column 0 of the row is the separator color; letters start at 1
            val background = pixelAt(0, y)
            val sprites = mutableMapOf<Char, Sprite>()
            var x = 1
            for (letter in LETTERS) {
                if (x >= width) return null
                var end = x
                while (end < width && pixelAt(end, y) != background) end++
                val letterWidth = end - x
                if (letterWidth <= 0) return null
                sprites[letter] = Sprite(x, y, letterWidth, LETTER_H)
                x = end + 1
            }
            return sprites
        }
    }
}
