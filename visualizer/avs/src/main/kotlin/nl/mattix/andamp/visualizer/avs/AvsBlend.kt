// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * How an Effect List folds what its children drew back into what was there.
 *
 * The names and their numbering are the format's, transcribed from
 * grandchild/AVS-File-Decoder (MIT). The arithmetic is transcribed from
 * vis_avs (BSD; see NOTICE.md): `blend.cpp`/`blend.h` for the per-pixel forms,
 * `e_effectlist.cpp` and `e_buffersave.cpp` for the decodes.
 */
enum class AvsBlendMode {
    /** Keep what was already there; the child's output is thrown away. */
    IGNORE,

    /** The child's output, as it is. */
    REPLACE,
    FIFTY_FIFTY,
    MAXIMUM,
    ADDITIVE,

    /** Destination minus source, per channel, clamped at black. */
    SUB_DEST_SRC,

    /** Source minus destination, per channel, clamped at black. */
    SUB_SRC_DEST,

    /** The child on even rows, what was there on odd ones. */
    EVERY_OTHER_LINE,

    /** A checkerboard: the source owns the top-left pixel, offset one column on odd rows. */
    EVERY_OTHER_PIXEL,
    XOR,
    MULTIPLY,

    /** The darker channel from either side. */
    MINIMUM,

    /** A weighted mix, the weight being the list's own adjustable value. */
    ADJUSTABLE,

    /**
     * A per-pixel blend whose weight is the brightest channel of a global
     * buffer's pixel. [AvsBlend.blend] has no buffer to weigh by, so there it
     * is a copy; [AvsEngine] does the masked blend for an Effect List.
     */
    BUFFER,
    ;

    companion object {
        /**
         * The effect list's own enum order, `e_effectlist.h` `LIST_BLEND_*`.
         * The file stores the input code directly (`e_effectlist.cpp`
         * `load_legacy`: `mode[1] & 0b111111`).
         */
        private val IN =
            listOf(
                IGNORE,
                REPLACE,
                FIFTY_FIFTY,
                MAXIMUM,
                ADDITIVE,
                SUB_DEST_SRC,
                SUB_SRC_DEST,
                EVERY_OTHER_LINE,
                EVERY_OTHER_PIXEL,
                XOR,
                ADJUSTABLE,
                MULTIPLY,
                BUFFER,
                MINIMUM,
            )

        /**
         * Buffer Save's numbering. Transcribed from `e_buffersave.cpp`
         * `load_legacy`'s switch.
         */
        private val BUFFER_LIST =
            listOf(
                REPLACE,
                FIFTY_FIFTY,
                ADDITIVE,
                EVERY_OTHER_PIXEL,
                SUB_DEST_SRC,
                EVERY_OTHER_LINE,
                XOR,
                MAXIMUM,
                MINIMUM,
                SUB_SRC_DEST,
                MULTIPLY,
                ADJUSTABLE,
            )

        /**
         * Set Render Mode's numbering, `blend.h` `blend_default_1px`: modes 0
         * through 9, and anything above falls to the `default:` replace case.
         */
        private val RENDER_LIST =
            listOf(
                REPLACE,
                ADDITIVE,
                MAXIMUM,
                FIFTY_FIFTY,
                SUB_DEST_SRC,
                SUB_SRC_DEST,
                MULTIPLY,
                ADJUSTABLE,
                XOR,
                MINIMUM,
            )

        /** An unlisted code leaves the canvas alone (`e_effectlist.cpp`'s `default: break`). */
        fun incoming(code: Int) = IN.getOrNull(code) ?: IGNORE

        /**
         * The outgoing numbering is the incoming one with each adjacent pair
         * swapped: `e_effectlist.cpp` `load_legacy` decodes it as
         * `(mode[2] & 0b111111) ^ 1`. So REPLACE is 0 coming out where it was 1
         * going in, and MINIMUM sits at 12 where BUFFER is 13. An unlisted code
         * leaves the target frame alone, same `default: break` as incoming.
         */
        fun outgoing(code: Int) = IN.getOrNull(code xor 1) ?: IGNORE

        /** How Buffer Save folds a buffer into the frame. */
        fun buffered(code: Int) = BUFFER_LIST.getOrNull(code) ?: REPLACE

        /** How a Render component puts its marks down, set by Set Render Mode. */
        fun rendering(code: Int) = RENDER_LIST.getOrNull(code) ?: REPLACE
    }
}

/**
 * Applies a [AvsBlendMode] over whole frames, because two of them depend on
 * position and one on a third frame.
 *
 * Arithmetic transcribed from vis_avs `blend.cpp` (BSD; see NOTICE.md), from
 * the plain-C forms.
 */
object AvsBlend {
    private const val CHANNELS = 0x00FFFFFF
    private const val FULL = 255

    /**
     * Folds [source] into [destination], in place.
     *
     * @param adjust the weight for [AvsBlendMode.ADJUSTABLE], 0..255, where 255
     *   is all source. Ignored by every other mode.
     */
    fun blend(
        mode: AvsBlendMode,
        source: AvsFrame,
        destination: AvsFrame,
        adjust: Int = FULL,
    ) {
        require(source.sameSizeAs(destination)) { "cannot blend frames of different sizes" }
        @Suppress("NAME_SHADOWING") // the clamped value replaces the parameter: an unclamped adjust corrupts the blend
        val adjust = adjust.coerceIn(0, FULL)
        when (mode) {
            AvsBlendMode.IGNORE -> return
            AvsBlendMode.REPLACE, AvsBlendMode.BUFFER -> destination.copyFrom(source)
            AvsBlendMode.EVERY_OTHER_LINE -> byLine(source, destination)
            AvsBlendMode.EVERY_OTHER_PIXEL -> byPixel(source, destination)
            else -> perPixel(mode, source, destination, adjust)
        }
    }

    /**
     * The BUFFER blend, `blend.cpp` `blend_buffer_rgb0_8_c`: an adjustable
     * blend whose weight, per pixel, is the brightest channel of [mask]'s
     * pixel. The result is source times the weight plus destination times its
     * complement, the two weights swapped when [invert] is set.
     */
    fun bufferBlend(
        source: AvsFrame,
        destination: AvsFrame,
        mask: AvsFrame,
        invert: Boolean = false,
    ) {
        require(source.sameSizeAs(destination) && mask.sameSizeAs(destination)) { "cannot blend frames of different sizes" }
        for (i in destination.pixels.indices) {
            destination.pixels[i] = bufferPixel(source.pixels[i], destination.pixels[i], mask.pixels[i], invert)
        }
    }

    /** One pixel of [bufferBlend]. */
    fun bufferPixel(
        source: Int,
        destination: Int,
        mask: Int,
        invert: Boolean = false,
    ): Int {
        var v = maxOf(redOf(mask), greenOf(mask), blueOf(mask))
        var iv = FULL - v
        if (invert) {
            iv = v
            v = FULL - v
        }
        // each product floors separately, like the C original's two LUT reads
        val r = (redOf(source) * v) / FULL + (redOf(destination) * iv) / FULL
        val g = (greenOf(source) * v) / FULL + (greenOf(destination) * iv) / FULL
        val b = (blueOf(source) * v) / FULL + (blueOf(destination) * iv) / FULL
        return AvsFrame.OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private fun perPixel(
        mode: AvsBlendMode,
        source: AvsFrame,
        destination: AvsFrame,
        adjust: Int,
    ) {
        for (i in destination.pixels.indices) {
            destination.pixels[i] = pixel(mode, source.pixels[i], destination.pixels[i], adjust)
        }
    }

    private fun byLine(
        source: AvsFrame,
        destination: AvsFrame,
    ) {
        for (y in 0 until destination.height step 2) {
            val row = y * destination.width
            source.pixels.copyInto(destination.pixels, row, row, row + destination.width)
        }
    }

    /**
     * A checkerboard, per `blend.cpp` `blend_every_other_pixel`: "The top-left
     * pixel is from src", and the copied column shifts by one on odd rows.
     */
    private fun byPixel(
        source: AvsFrame,
        destination: AvsFrame,
    ) {
        for (y in 0 until destination.height) {
            val row = y * destination.width
            var x = y and 1
            while (x < destination.width) {
                destination.pixels[row + x] = source.pixels[row + x]
                x += 2
            }
        }
    }

    /** One pixel, for the modes that are pure arithmetic. */
    fun pixel(
        mode: AvsBlendMode,
        source: Int,
        destination: Int,
        adjust: Int = FULL,
    ): Int =
        when (mode) {
            AvsBlendMode.IGNORE -> destination

            AvsBlendMode.REPLACE, AvsBlendMode.BUFFER,
            AvsBlendMode.EVERY_OTHER_LINE, AvsBlendMode.EVERY_OTHER_PIXEL,
            -> source

            AvsBlendMode.XOR -> AvsFrame.OPAQUE or ((source xor destination) and CHANNELS)

            else -> channelwise(mode, source, destination, adjust)
        }

    // unrolled: this runs per pixel per frame, and a loop over an
    // intArrayOf(16, 8, 0) is an allocation per pixel
    private fun channelwise(
        mode: AvsBlendMode,
        source: Int,
        destination: Int,
        adjust: Int,
    ): Int {
        val r = channel(mode, (source shr 16) and 0xFF, (destination shr 16) and 0xFF, adjust)
        val g = channel(mode, (source shr 8) and 0xFF, (destination shr 8) and 0xFF, adjust)
        val b = channel(mode, source and 0xFF, destination and 0xFF, adjust)
        return AvsFrame.OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private fun channel(
        mode: AvsBlendMode,
        s: Int,
        d: Int,
        adjust: Int,
    ): Int =
        when (mode) {
            // (a >> 1) + (b >> 1), each low bit discarded first: the MMX
            // original's form, up to one darker than a true average
            AvsBlendMode.FIFTY_FIFTY -> (s shr 1) + (d shr 1)

            AvsBlendMode.MAXIMUM -> maxOf(s, d)

            AvsBlendMode.MINIMUM -> minOf(s, d)

            AvsBlendMode.ADDITIVE -> minOf(FULL, s + d)

            AvsBlendMode.SUB_DEST_SRC -> maxOf(0, d - s)

            AvsBlendMode.SUB_SRC_DEST -> maxOf(0, s - d)

            AvsBlendMode.MULTIPLY -> s * d / FULL

            // two LUT reads in the original, so the two products floor separately
            AvsBlendMode.ADJUSTABLE -> (s * adjust) / FULL + (d * (FULL - adjust)) / FULL

            else -> error("$mode is not a per-channel blend")
        }
}
