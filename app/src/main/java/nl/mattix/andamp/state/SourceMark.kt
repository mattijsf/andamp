// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * The small picture a source's rows carry where more than one source is shown: in the
 * library's every-source view and, when the listener wants it, in the playlist.
 *
 * It comes in two forms, one per list font. [pixels] is 1-bit pixel art for the skin's font,
 * written one pixel to the virtual pixel like the font's letters, at most [MAX_W] wide and
 * [MAX_H] tall: `#` is ink, anything else is not. [path] is the same picture as vector path
 * data (the `d` of an SVG path, in a [VIEWPORT]-unit square) for the outline font, so it scales
 * with the text. Where a form is missing, [letter] stands in for it, in the font's own letters.
 */
class SourceMark(
    val pixels: List<String> = emptyList(),
    val path: String? = null,
    val letter: String,
) {
    /** How wide the pixel art is, in pixels; zero without any. */
    val pixelWidth: Int = pixels.maxOfOrNull { it.length } ?: 0

    /** Whether the pixel at ([x], [y]) is ink. */
    fun ink(
        x: Int,
        y: Int,
    ): Boolean = pixels.getOrNull(y)?.getOrNull(x) == '#'

    override fun equals(other: Any?) =
        other is SourceMark && other.pixels == pixels && other.path == path && other.letter == letter

    override fun hashCode() = (pixels.hashCode() * PRIME + (path?.hashCode() ?: 0)) * PRIME + letter.hashCode()

    companion object {
        /** The widest pixel art a mark has: a letter of the skin's font is five. */
        const val MAX_W = 7

        /** The tallest: a letter of the skin's font has five rows of ink over a blank one. */
        const val MAX_H = 5

        /** The square a [path] is drawn in, as an Android vector drawable's viewport. */
        const val VIEWPORT = 24f

        private const val PRIME = 31

        /** The phone's own mark: a handset with its home button. */
        val PHONE =
            SourceMark(
                pixels =
                    listOf(
                        "#####",
                        "#...#",
                        "#...#",
                        "#...#",
                        "##.##",
                    ),
                // a handset's outline with its screen cut out and a button under it, drawn even-odd
                path = "M7 1h10a2 2 0 0 1 2 2v18a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V3a2 2 0 0 1 2-2zM7 4v14h10V4zM11 19.5h2v1.5h-2z",
                letter = "P",
            )

        /** A mark of only a letter, for a source that gives no picture of its own. */
        fun letter(text: String) = SourceMark(letter = text.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?")

        /**
         * The pixel art a source gives, checked: rows of `#` and `.` no larger than
         * [MAX_W] by [MAX_H]. Anything else is no pixel art, and the letter stands in.
         */
        fun pixelsOrEmpty(rows: List<String>): List<String> =
            rows.takeIf { art ->
                art.size in 1..MAX_H && art.all { row -> row.length <= MAX_W && row.all { it == '#' || it == '.' } }
            } ?: emptyList()
    }
}

/** The mark of each source: the phone's own, and a letter for every other until it gives one. */
fun markOf(
    source: MusicSource,
    words: Words,
): SourceMark = if (source == MusicSource.LOCAL) SourceMark.PHONE else SourceMark.letter(source.shown(words))
