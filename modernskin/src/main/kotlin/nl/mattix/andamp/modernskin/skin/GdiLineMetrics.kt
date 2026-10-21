// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import kotlin.math.ceil

/**
 * A TrueType font's line as Windows lays it out: GDI places text from the OS/2 table's
 * usWinAscent and usWinDescent, each rounded up to whole pixels, where Java and Android use the
 * hhea table (a pixel less for Microsoft Sans Serif at 11 px). Renderers read it from the font file.
 */
class GdiLineMetrics private constructor(
    private val unitsPerEm: Int,
    private val winAscent: Int,
    private val winDescent: Int,
) {
    /** Ascent and descent in pixels at an em of [px]. */
    fun at(px: Float): Pair<Int, Int> = ceil(winAscent * px / unitsPerEm).toInt() to ceil(winDescent * px / unitsPerEm).toInt()

    companion object {
        /** From a TrueType file's bytes; null when it has no head or OS/2 table. */
        fun read(bytes: ByteArray): GdiLineMetrics? {
            fun u16(o: Int) =
                if (o + 2 <=
                    bytes.size
                ) {
                    ((bytes[o].toInt() and 0xff) shl 8) or (bytes[o + 1].toInt() and 0xff)
                } else {
                    -1
                }

            fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
            val tables = u16(4)
            if (tables <= 0) return null
            var head = -1
            var os2 = -1
            for (i in 0 until tables) {
                val rec = 12 + i * 16
                val tag = String(bytes, rec, 4, Charsets.ISO_8859_1)
                val offset = u32(rec + 8).toInt()
                when (tag) {
                    "head" -> head = offset
                    "OS/2" -> os2 = offset
                }
            }
            if (head < 0 || os2 < 0) return null
            val upem = u16(head + 18)
            val ascent = u16(os2 + 74)
            val descent = u16(os2 + 76)
            if (upem <= 0 || ascent < 0 || descent < 0) return null
            return GdiLineMetrics(upem, ascent, descent)
        }
    }
}
