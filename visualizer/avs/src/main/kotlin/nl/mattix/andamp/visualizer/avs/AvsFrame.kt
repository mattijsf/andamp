// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * One framebuffer: `width * height` pixels, `0xAARRGGBB`, alpha always opaque.
 *
 * AVS stores colors as `0x00RRGGBB`, which is Android's channel order, so
 * reading a config color only sets the alpha byte. See [fromConfig].
 */
class AvsFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray = IntArray(width * height) { OPAQUE },
) {
    init {
        require(width > 0 && height > 0) { "a frame is at least one pixel: got ${width}x$height" }
        require(pixels.size == width * height) { "expected ${width * height} pixels, got ${pixels.size}" }
    }

    operator fun get(
        x: Int,
        y: Int,
    ): Int = pixels[y * width + x]

    operator fun set(
        x: Int,
        y: Int,
        colour: Int,
    ) {
        pixels[y * width + x] = colour
    }

    /** Black, which is what a cleared AVS frame is. */
    fun clear() = pixels.fill(OPAQUE)

    fun copyFrom(other: AvsFrame) {
        require(sameSizeAs(other)) { "cannot copy a ${other.width}x${other.height} frame into a ${width}x$height one" }
        other.pixels.copyInto(pixels)
    }

    fun sameSizeAs(other: AvsFrame) = width == other.width && height == other.height

    companion object {
        const val OPAQUE = 0xFF000000.toInt()

        /**
         * A color as an AVS config writes it: `0x00RRGGBB`, made opaque.
         *
         * The channel order needs no swap: the file holds (A)RGB, not a Windows
         * COLORREF. Transcribed from AVS-File-Decoder's `get.ts` `Color` (MIT),
         * which reads the little-endian int and prints its hex as `#rrggbb`.
         */
        fun fromConfig(rgb: Int): Int = OPAQUE or (rgb and 0x00FFFFFF)
    }
}

// The three channels of a pixel, and a pixel from three channels. These run per
// pixel per frame, so they are functions and not a destructured Triple, which
// would allocate.

internal fun redOf(pixel: Int) = (pixel shr 16) and 0xFF

internal fun greenOf(pixel: Int) = (pixel shr 8) and 0xFF

internal fun blueOf(pixel: Int) = pixel and 0xFF

internal fun pixelOf(
    red: Int,
    green: Int,
    blue: Int,
) = AvsFrame.OPAQUE or
    (red.coerceIn(0, 255) shl 16) or
    (green.coerceIn(0, 255) shl 8) or
    blue.coerceIn(0, 255)
