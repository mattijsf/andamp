// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs.author

import java.io.ByteArrayOutputStream

/**
 * Builds a component body a field at a time: the writing counterpart of the
 * module's `BodyReader` and of Super Scope's `Cursor`, with the same field
 * shapes: little-endian int32s, IEEE floats stored as their bits, colors as
 * `0x00RRGGBB`, strings either length-prefixed or padded into a fixed block.
 * Calls chain.
 */
class BodyWriter {
    private val out = ByteArrayOutputStream()

    /** Four bytes, least significant first. */
    fun int32(value: Int): BodyWriter {
        out.write(value)
        out.write(value shr 8)
        out.write(value shr 16)
        out.write(value shr 24)
        return this
    }

    /** One byte: the low eight bits of [value]. */
    fun byte(value: Int): BodyWriter {
        out.write(value)
        return this
    }

    /** A 4-byte IEEE float, stored as its bit pattern, as `BodyReader.float` reads it. */
    fun float(value: Float): BodyWriter = int32(value.toRawBits())

    /**
     * One color int32, `0x00RRGGBB`. The readers ignore the top byte
     * (`AvsFrame.fromConfig` masks it off), so it is written as zero.
     */
    fun colour(rgb: Int): BodyWriter = int32(rgb and CHANNEL_MASK)

    /**
     * A count and then that many colors. A count past 16 makes the readers
     * load nothing without consuming the color ints, which misaligns every
     * field after it, so a longer list is refused.
     */
    fun colourList(colours: List<Int>): BodyWriter {
        require(
            colours.size <= MAX_COLOURS,
        ) { "${colours.size} colours would desync every reader; the format caps at $MAX_COLOURS" }
        int32(colours.size)
        colours.forEach { colour(it) }
        return this
    }

    /**
     * A length-prefixed string: int32 length, then that many bytes. The length
     * counts a trailing null, which is written: readers stop at the first null
     * but consume the declared bytes, and vis_avs stores `strlen + 1`.
     */
    fun sizedString(text: String): BodyWriter {
        val encoded = text.toByteArray(Charsets.ISO_8859_1)
        int32(encoded.size + 1)
        bytes(encoded)
        return byte(0)
    }

    /**
     * A string padded with nulls into a fixed [size]-byte block, such as an
     * APE's 32-byte name.
     */
    fun fixedString(
        text: String,
        size: Int,
    ): BodyWriter {
        val encoded = text.toByteArray(Charsets.ISO_8859_1)
        require(encoded.size <= size) { "'$text' is ${encoded.size} bytes and cannot fit a $size-byte block" }
        bytes(encoded)
        bytes(ByteArray(size - encoded.size))
        return this
    }

    /** Raw bytes, as they are. */
    fun bytes(raw: ByteArray): BodyWriter {
        out.write(raw, 0, raw.size)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private companion object {
        const val CHANNEL_MASK = 0xFFFFFF

        /** AVS's own limit; see `BodyFields.colourList` and `e_superscope.cpp:295`. */
        const val MAX_COLOURS = 16
    }
}
