// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * Whether a picture's header asks for more pixels than a skin picture can need, read before
 * decoding: a file that claims 50000 x 50000 pixels would take gigabytes. PNG, BMP, GIF and JPEG
 * headers are read; anything else passes and is left to the decoder.
 */
object ImageLimits {
    const val MAX_SIDE = 8_192
    const val MAX_PIXELS = 16L * 1024 * 1024

    fun tooLarge(bytes: ByteArray): Boolean {
        val (w, h) = size(bytes) ?: return false
        return w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS
    }

    /** Width and height from the header, or null when the format is not one of the four. */
    fun size(b: ByteArray): Pair<Int, Int>? =
        when {
            b.size >= 24 && b.u8(0) == 0x89 && b.u8(1) == 'P'.code && b.u8(2) == 'N'.code && b.u8(3) == 'G'.code -> {
                b.be32(16) to b.be32(20)
            }

            b.size >= 26 && b.u8(0) == 'B'.code && b.u8(1) == 'M'.code -> {
                b.le32(18) to kotlin.math.abs(b.le32(22))
            }

            b.size >= 10 && b.u8(0) == 'G'.code && b.u8(1) == 'I'.code && b.u8(2) == 'F'.code -> {
                b.le16(6) to b.le16(8)
            }

            b.size >= 4 && b.u8(0) == 0xff && b.u8(1) == 0xd8 -> {
                jpegSize(b)
            }

            else -> {
                null
            }
        }

    /** The byte at [i] as 0..255, or -1 past the end. */
    private fun ByteArray.u8(i: Int) = if (i < size) this[i].toInt() and 0xff else -1

    private fun ByteArray.be16(i: Int) = (u8(i) shl 8) or u8(i + 1)

    private fun ByteArray.be32(i: Int) = (be16(i) shl 16) or be16(i + 2)

    private fun ByteArray.le16(i: Int) = u8(i) or (u8(i + 1) shl 8)

    private fun ByteArray.le32(i: Int) = le16(i) or (le16(i + 2) shl 16)

    /** A JPEG's size from its first start-of-frame marker; null at the first byte that is not a marker. */
    private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < b.size && (b[i].toInt() and 0xff) == 0xff) {
            val marker = b[i + 1].toInt() and 0xff
            val len = ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
            if (isStartOfFrame(marker)) {
                val h = ((b[i + 5].toInt() and 0xff) shl 8) or (b[i + 6].toInt() and 0xff)
                val w = ((b[i + 7].toInt() and 0xff) shl 8) or (b[i + 8].toInt() and 0xff)
                return w to h
            }
            if (len < 2) return null
            i += 2 + len
        }
        return null
    }

    /** SOF0 to SOF15, less DHT (C4), JPG (C8) and DAC (CC), which share the range. */
    private fun isStartOfFrame(marker: Int) = marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
}
