// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

// Components that were plug-ins (APEs) for the original AVS and are built in
// here: Multiplier, Normalise, Add Borders, MultiFilter and Framerate Limiter.
// Transcribed from the vis_avs source (BSD 3-clause; see NOTICE.md), which
// carries Multiplier from Nullsoft's own tree and rewrites of the others.

/**
 * Multiplies every channel by a power of two, or pushes each pixel to one of
 * the two ends.
 *
 * Transcribed from vis_avs `e_multiplier.cpp`. "Infinite root" keeps white
 * and turns everything else black; "infinite square" keeps black and turns
 * everything else white.
 */
internal class MultiplierRenderer(
    private val mode: Int,
) : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        val pixels = frame.pixels
        when (mode) {
            INFINITE_ROOT -> for (i in pixels.indices) if (pixels[i] and RGB != RGB) pixels[i] = AvsFrame.OPAQUE
            INFINITE_SQUARE -> for (i in pixels.indices) if (pixels[i] and RGB != 0) pixels[i] = AvsFrame.OPAQUE or RGB
            in TIMES_8..TIMES_2 -> shift(pixels, left = TIMES_2 - mode + 1)
            in HALF..EIGHTH -> shift(pixels, left = HALF - mode - 1)
        }
    }

    /** Every channel shifted [left] bits (right when negative), stopping at full. */
    private fun shift(
        pixels: IntArray,
        left: Int,
    ) {
        for (i in pixels.indices) {
            val pixel = pixels[i]
            pixels[i] =
                if (left >= 0) {
                    pixelOf(redOf(pixel) shl left, greenOf(pixel) shl left, blueOf(pixel) shl left)
                } else {
                    pixelOf(redOf(pixel) shr -left, greenOf(pixel) shr -left, blueOf(pixel) shr -left)
                }
        }
    }

    companion object {
        private const val INFINITE_ROOT = 0
        private const val TIMES_8 = 1
        private const val TIMES_2 = 3
        private const val HALF = 4
        private const val EIGHTH = 6
        private const val INFINITE_SQUARE = 7
        private const val RGB = 0xFFFFFF

        /** A new Multiplier doubles. */
        fun read(body: ByteArray) = MultiplierRenderer(BodyReader(body).int32Or(TIMES_2))
    }
}

/**
 * Stretches the frame's brightness to the full range: the darkest channel
 * value anywhere becomes 0 and the brightest 255.
 *
 * Transcribed from vis_avs `e_normalise.cpp`. One table serves all three
 * channels, so colors keep their hue. A frame of one flat level goes black.
 */
internal class NormaliseRenderer(
    private val enabled: Boolean,
) : AvsComponentRenderer {
    private val table = IntArray(LEVELS)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        val pixels = frame.pixels
        var lowest = LEVELS - 1
        var highest = 0
        for (pixel in pixels) {
            val red = redOf(pixel)
            val green = greenOf(pixel)
            val blue = blueOf(pixel)
            highest = maxOf(highest, red, green, blue)
            lowest = minOf(lowest, red, green, blue)
        }
        if (highest == LEVELS - 1 && lowest == 0) return
        table.fill(0)
        if (highest > lowest) {
            val stretch = (LEVELS - 1) / (highest - lowest).toFloat()
            for (level in lowest..highest) table[level] = ((level - lowest) * stretch).toInt()
        }
        for (i in pixels.indices) {
            val pixel = pixels[i]
            pixels[i] = pixelOf(table[redOf(pixel)], table[greenOf(pixel)], table[blueOf(pixel)])
        }
    }

    companion object {
        private const val LEVELS = 256

        /** Off unless the body says on. */
        fun read(body: ByteArray) = NormaliseRenderer(enabled = BodyReader(body).int32Or(0) != 0)
    }
}

/**
 * A frame of one color around the picture, as wide as a percentage of each
 * side and at least a pixel.
 *
 * Transcribed from vis_avs `e_addborders.cpp`.
 */
internal class AddBordersRenderer(
    private val enabled: Boolean,
    private val colour: Int,
    private val percent: Int,
) : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        val w = frame.width
        val h = frame.height
        val rows = (h * percent / HUNDRED).coerceIn(1, h)
        val columns = (w * percent / HUNDRED).coerceIn(1, w)
        for (y in 0 until h) {
            val edgeRow = y < rows || y >= h - rows
            for (x in 0 until w) {
                if (edgeRow || x < columns || x >= w - columns) frame[x, y] = colour
            }
        }
    }

    companion object {
        private const val HUNDRED = 100
        private const val WIDEST = 50

        fun read(body: ByteArray): AddBordersRenderer {
            val reader = BodyReader(body)
            val enabled = reader.int32Or(0) != 0
            val colour = AvsFrame.fromConfig(reader.int32Or(0))
            val percent = reader.int32Or(1).coerceIn(1, WIDEST)
            return AddBordersRenderer(enabled, colour, percent)
        }
    }
}

/**
 * Four fixed filters under one name.
 *
 * Transcribed from vis_avs `e_multifilter.cpp`. Chrome folds every channel
 * around its middle, so mid-tones come out brightest and both ends dark;
 * double and triple chrome fold again. The fourth turns every pixel that is
 * not black white, together with the pixel above it and the one to its left.
 * With the toggle set, each beat switches the filter on or off, starting off.
 */
internal class MultiFilterRenderer(
    private val enabled: Boolean,
    private val effect: Int,
    private val toggleOnBeat: Boolean,
) : AvsComponentRenderer {
    private var toggledOn = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        if (toggleOnBeat) {
            if (state.beat) toggledOn = !toggledOn
            if (!toggledOn) return
        }
        if (effect == BORDER) border(frame) else chrome(frame.pixels, folds = effect + 1)
    }

    private fun chrome(
        pixels: IntArray,
        folds: Int,
    ) {
        for (i in pixels.indices) {
            val pixel = pixels[i]
            pixels[i] = pixelOf(FOLDED[folds][redOf(pixel)], FOLDED[folds][greenOf(pixel)], FOLDED[folds][blueOf(pixel)])
        }
    }

    private fun border(frame: AvsFrame) {
        val w = frame.width
        val pixels = frame.pixels
        for (at in pixels.indices) {
            if (pixels[at] and RGB != 0) {
                pixels[at] = WHITE
                if (at >= w) pixels[at - w] = WHITE
                if (at % w > 0) pixels[at - 1] = WHITE
            } else {
                pixels[at] = AvsFrame.OPAQUE
            }
        }
    }

    companion object {
        private const val BORDER = 3
        private const val LEVELS = 256
        private const val RGB = 0xFFFFFF
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** A channel folded around its middle, by how many folds: index 1 to 3. */
        private val FOLDED: Array<IntArray> =
            Array(BORDER + 1) { folds ->
                IntArray(LEVELS) { level ->
                    var value = level
                    repeat(folds) { value = if (value < LEVELS / 2) value * 2 else (LEVELS - 1) * 2 - value * 2 }
                    value
                }
            }

        fun read(body: ByteArray): MultiFilterRenderer {
            val reader = BodyReader(body)
            val enabled = reader.int32Or(1) != 0
            // an effect the list does not have is plain chrome
            val effect = reader.int32Or(0).takeIf { it in 0..BORDER } ?: 0
            val toggle = reader.int32Or(0) != 0
            return MultiFilterRenderer(enabled, effect, toggle)
        }
    }
}

/**
 * Framerate Limiter holds a preset back to a number of frames a second by
 * sleeping. The view paces its own frames, so here it draws nothing and waits
 * for nothing; it is accepted so that a preset using it has nothing missing.
 */
internal class FramerateLimiterRenderer : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) = Unit
}
