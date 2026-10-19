// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * The small picture a source's rows carry where more than one source is shown: in the
 * library's every-source view and, when the listener wants it, in the playlist.
 *
 * It comes in two forms, one per list font. [pixels] is 1-bit pixel art for the skin's font,
 * written one pixel to the virtual pixel like the font's letters, at most [MAX_W] wide and
 * [MAX_H] tall, centered on the letters' [LETTER_H] rows: `#` is ink, anything else is not.
 * Art taller than a letter stands out above and below it, room a logo needs to be told apart.
 *
 * For the outline font the picture is drawn as tall as the letters, so it scales with the text:
 * as vector path data ([path], the `d` of an SVG path in a [VIEWPORT]-unit square) for a mark
 * built in, or as a shape ([mask], an `ALPHA_8` bitmap cropped to its ink) for one read from a
 * pack's app ([PackMarks]). Where a form is missing, [letter] stands in for it, in the font's
 * letters.
 */
class SourceMark(
    val pixels: List<String> = emptyList(),
    val path: String? = null,
    val letter: String,
    val mask: android.graphics.Bitmap? = null,
) {
    /** How wide the pixel art is, in pixels; zero without any. */
    val pixelWidth: Int = pixels.maxOfOrNull { it.length } ?: 0

    /** Whether the pixel at ([x], [y]) is ink. */
    fun ink(
        x: Int,
        y: Int,
    ): Boolean = pixels.getOrNull(y)?.getOrNull(x) == '#'

    // a mask is read once per pack and kept ([PackMarks]), so the same picture is the same bitmap
    override fun equals(other: Any?) =
        other is SourceMark && other.pixels == pixels && other.path == path && other.letter == letter && other.mask === mask

    override fun hashCode() =
        ((pixels.hashCode() * PRIME + (path?.hashCode() ?: 0)) * PRIME + letter.hashCode()) * PRIME +
            System.identityHashCode(mask)

    companion object {
        /** The widest pixel art a mark has: a letter of the skin's font is five. */
        const val MAX_W = 9

        /** The tallest: two rows above a letter and two below, inside the shortest row of a list. */
        const val MAX_H = 9

        /** How many rows of ink a letter of the skin's font has, over a blank one. */
        const val LETTER_H = 5

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

/**
 * The mark of each source: the phone's, the one a pack's app gives or suggests ([packMark]), and
 * else the first letter of its name.
 */
fun markOf(
    source: MusicSource,
    words: Words,
    packMark: (MusicSource) -> SourceMark? = { null },
): SourceMark =
    when {
        source == MusicSource.LOCAL -> SourceMark.PHONE
        else -> packMark(source) ?: SourceMark.letter(source.shown(words))
    }
