// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/** A picture a preset draws with: `width * height` pixels, `0xAARRGGBB`, opaque. */
class AvsImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(
            width > 0 && height > 0 && pixels.size == width * height,
        ) { "a ${width}x$height image with ${pixels.size} pixels" }
    }

    /** The pixel at ([x], [y]), the edge pixel for a place outside. */
    fun at(
        x: Int,
        y: Int,
    ): Int = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    /**
     * The color at a place between pixels: the four around it, weighted by
     * how near each is. The place is in 16.16 fixed point, so a caller that
     * walks across a picture adds a step and converts nothing.
     */
    fun between(
        x: Int,
        y: Int,
    ): Int {
        val left = x shr FIXED
        val top = y shr FIXED
        val across = (x shr (FIXED - WEIGHT_BITS)) and (WEIGHTS - 1)
        val down = (y shr (FIXED - WEIGHT_BITS)) and (WEIGHTS - 1)
        val x0 = left.coerceIn(0, width - 1)
        val x1 = (left + 1).coerceIn(0, width - 1)
        val row0 = top.coerceIn(0, height - 1) * width
        val row1 = (top + 1).coerceIn(0, height - 1) * width
        val a = pixels[row0 + x0]
        val b = pixels[row0 + x1]
        val c = pixels[row1 + x0]
        val d = pixels[row1 + x1]
        val keep = WEIGHTS - across
        val stay = WEIGHTS - down
        val red =
            (
                (((a shr RED_SHIFT) and BYTE) * keep + ((b shr RED_SHIFT) and BYTE) * across) * stay +
                    (((c shr RED_SHIFT) and BYTE) * keep + ((d shr RED_SHIFT) and BYTE) * across) * down
            ) shr (2 * WEIGHT_BITS)
        val green =
            (
                (((a shr GREEN_SHIFT) and BYTE) * keep + ((b shr GREEN_SHIFT) and BYTE) * across) * stay +
                    (((c shr GREEN_SHIFT) and BYTE) * keep + ((d shr GREEN_SHIFT) and BYTE) * across) * down
            ) shr (2 * WEIGHT_BITS)
        val blue =
            (
                ((a and BYTE) * keep + (b and BYTE) * across) * stay +
                    ((c and BYTE) * keep + (d and BYTE) * across) * down
            ) shr (2 * WEIGHT_BITS)
        return AvsFrame.OPAQUE or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }

    /** [between] for a place given in pixels. */
    fun between(
        x: Double,
        y: Double,
    ): Int = between((x * ONE).toInt(), (y * ONE).toInt())

    private companion object {
        const val WEIGHT_BITS = 8
        const val FIXED = 16
        const val ONE = 65_536.0
        const val WEIGHTS = 1 shl WEIGHT_BITS
        const val BYTE = 0xFF
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
    }
}

/**
 * Where a preset's pictures come from. AVS looks a picture up by file name in
 * its own folder; here the name is looked up among the files of the preset's
 * pack. Null when there is no such picture, or it cannot be read.
 */
fun interface AvsImages {
    fun load(name: String): AvsImage?

    companion object {
        /** No pictures at all: what an engine has until it is given a source. */
        val NONE = AvsImages { null }
    }
}

/**
 * A component that draws with a picture. The engine hands it the pictures
 * when the preset is loaded, and lists the component as missing something
 * when the picture it names is not there.
 */
internal interface AvsPictured {
    /** Takes what it needs from [images]. Answers the file name it could not get, or null. */
    fun attach(images: AvsImages): String?
}
