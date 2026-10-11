// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import java.util.Random

/**
 * Recolors the frame through a gradient: a key is taken from each pixel, and
 * the color at that place in the gradient is blended back over the pixel.
 *
 * After vis_avs `e_colormap.cpp` (BSD; see NOTICE.md), a rewrite of the Color
 * Map plug-in. A preset carries up to eight gradients, each a list of colors
 * at positions 0 to 255 with straight ramps between them. With cycling on,
 * each beat picks another enabled gradient, at random or in turn, and the
 * frame fades from the old one to the new at the cycle speed.
 */
internal class ColorMapRenderer(
    private val settings: Settings,
    private val maps: List<Map>,
    private val random: Random = Random(),
) : AvsComponentRenderer {
    internal data class Settings(
        val key: Int = KEY_RED,
        val blend: Int = BLEND_REPLACE,
        val cycle: Int = CYCLE_NONE,
        val adjust: Int = 0,
        val waitForFade: Boolean = false,
        val cycleSpeed: Int = 11,
    )

    /** One gradient: whether cycling may pick it, and its 256 colors. */
    internal class Map(
        val enabled: Boolean,
        val colours: IntArray,
    )

    private var current = maps.indexOfFirst { it.enabled }.coerceAtLeast(0)
    private var next = current
    private var fade = STEPS
    private val fading = IntArray(STEPS)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        val table = tableFor(state.beat)
        val pixels = frame.pixels
        for (i in pixels.indices) {
            val pixel = pixels[i]
            pixels[i] = blended(table[keyOf(pixel)], pixel)
        }
    }

    /** The gradient for this frame: the current one, or a fade toward the next. */
    private fun tableFor(beat: Boolean): IntArray {
        if (settings.cycle == CYCLE_NONE) return maps[current].colours
        fade = (fade + settings.cycleSpeed).coerceAtMost(STEPS)
        if (beat && (!settings.waitForFade || fade == STEPS)) {
            pickNext()
            fade = 0
        }
        if (fade == STEPS) current = next
        if (fade == 0 || fade == STEPS || current == next) return maps[current].colours
        val from = maps[current].colours
        val to = maps[next].colours
        for (i in 0 until STEPS) fading[i] = mix(to[i], from[i], fade)
        return fading
    }

    /** Another enabled gradient to fade to: any of them, or the one after the last. */
    private fun pickNext() {
        if (maps.none { it.enabled }) return
        do {
            next = if (settings.cycle == CYCLE_RANDOM) random.nextInt(maps.size) else (next + 1) % maps.size
        } while (!maps[next].enabled)
    }

    private fun keyOf(pixel: Int): Int =
        when (settings.key) {
            KEY_RED -> redOf(pixel)
            KEY_GREEN -> greenOf(pixel)
            KEY_BLUE -> blueOf(pixel)
            KEY_HALF_SUM -> ((redOf(pixel) + greenOf(pixel) + blueOf(pixel)) / 2).coerceAtMost(STEPS - 1)
            KEY_MAX -> maxOf(redOf(pixel), greenOf(pixel), blueOf(pixel))
            else -> (redOf(pixel) + greenOf(pixel) + blueOf(pixel)) / 3
        }

    private fun blended(
        mapped: Int,
        pixel: Int,
    ): Int =
        when (settings.blend) {
            BLEND_ADDITIVE -> {
                AvsBlend.pixel(AvsBlendMode.ADDITIVE, mapped, pixel, FULL)
            }

            BLEND_MAXIMUM -> {
                AvsBlend.pixel(AvsBlendMode.MAXIMUM, mapped, pixel, FULL)
            }

            BLEND_MINIMUM -> {
                AvsBlend.pixel(AvsBlendMode.MINIMUM, mapped, pixel, FULL)
            }

            BLEND_FIFTY_FIFTY -> {
                AvsBlend.pixel(AvsBlendMode.FIFTY_FIFTY, mapped, pixel, FULL)
            }

            BLEND_PIXEL_LESS_MAP -> {
                pixelOf(
                    redOf(pixel) - redOf(mapped),
                    greenOf(pixel) - greenOf(mapped),
                    blueOf(pixel) - blueOf(mapped),
                )
            }

            BLEND_MAP_LESS_PIXEL -> {
                pixelOf(
                    redOf(mapped) - redOf(pixel),
                    greenOf(mapped) - greenOf(pixel),
                    blueOf(mapped) - blueOf(pixel),
                )
            }

            BLEND_MULTIPLY -> {
                AvsBlend.pixel(AvsBlendMode.MULTIPLY, mapped, pixel, FULL)
            }

            BLEND_XOR -> {
                AvsBlend.pixel(AvsBlendMode.XOR, mapped, pixel, FULL)
            }

            BLEND_ADJUSTABLE -> {
                mix(mapped, pixel, settings.adjust)
            }

            else -> {
                mapped
            }
        }

    companion object {
        private const val STEPS = 256
        private const val FULL = 255
        private const val MAPS = 8
        private const val KEY_RED = 0
        private const val KEY_GREEN = 1
        private const val KEY_BLUE = 2
        private const val KEY_HALF_SUM = 3
        private const val KEY_MAX = 4
        private const val CYCLE_NONE = 0
        private const val CYCLE_RANDOM = 1
        private const val BLEND_REPLACE = 0
        private const val BLEND_ADDITIVE = 1
        private const val BLEND_MAXIMUM = 2
        private const val BLEND_MINIMUM = 3
        private const val BLEND_FIFTY_FIFTY = 4
        private const val BLEND_PIXEL_LESS_MAP = 5
        private const val BLEND_MAP_LESS_PIXEL = 6
        private const val BLEND_MULTIPLY = 7
        private const val BLEND_XOR = 8
        private const val BLEND_ADJUSTABLE = 9
        private const val BASE_SIZE = 16
        private const val HEADER_SIZE = 60
        private const val FILENAME_SIZE = 48
        private const val MAX_COLOURS = 256
        private const val INT_SIZE = 4
        private const val BLACK = 0x000000
        private const val WHITE = 0xFFFFFF

        /** [a] weighted [weight] of 255 against [b], a channel at a time. */
        internal fun mix(
            a: Int,
            b: Int,
            weight: Int,
        ): Int {
            val w = weight.coerceIn(0, FULL)
            return pixelOf(
                (redOf(a) * w + redOf(b) * (FULL - w)) / FULL,
                (greenOf(a) * w + greenOf(b) * (FULL - w)) / FULL,
                (blueOf(a) * w + blueOf(b) * (FULL - w)) / FULL,
            )
        }

        /**
         * The 256 colors of a gradient from its stops, each a position and a
         * color. Before the first stop and after the last the gradient is
         * flat.
         */
        internal fun bake(stops: List<Pair<Int, Int>>): IntArray {
            val sorted = stops.sortedWith(compareBy({ it.first }, { it.second }))
            val out = IntArray(STEPS) { AvsFrame.OPAQUE }
            if (sorted.isEmpty()) return out
            var at = 0
            while (at < STEPS && at < sorted.first().first) out[at++] = AvsFrame.fromConfig(sorted.first().second)
            for (index in 0 until sorted.size - 1) {
                val (fromAt, from) = sorted[index]
                val (toAt, to) = sorted[index + 1]
                while (at < STEPS && at < toAt) {
                    val weight = STEPS * (at - fromAt) / (toAt - fromAt)
                    out[at++] = mix(AvsFrame.fromConfig(to), AvsFrame.fromConfig(from), weight)
                }
            }
            while (at < STEPS) out[at++] = AvsFrame.fromConfig(sorted.last().second)
            return out
        }

        private val plain get() = bake(listOf(0 to BLACK, FULL to WHITE))

        /**
         * The body: the key, the blend, the cycle mode and four single bytes,
         * then eight map headers of 60 bytes (enabled, how many colors, an id
         * and a file name), then every map's colors in turn, each a position,
         * a color and an id. A body shorter than the first part is a new
         * Color Map: one black-to-white gradient keyed on red.
         */
        fun read(body: ByteArray): ColorMapRenderer {
            val fresh = List(MAPS) { Map(enabled = it == 0, colours = plain) }
            if (body.size < BASE_SIZE) return ColorMapRenderer(Settings(), fresh)
            val reader = BodyReader(body)
            val key = reader.int32()
            val blend = reader.int32()
            val cycle = reader.int32()
            val adjust = reader.byte()
            reader.byte()
            val waitForFade = reader.byte() != 0
            val cycleSpeed = reader.byte()
            val enabled = BooleanArray(MAPS) { it == 0 }
            val counts = IntArray(MAPS) { -1 }
            for (index in 0 until MAPS) {
                if (!reader.has(HEADER_SIZE)) break
                enabled[index] = reader.int32() != 0
                counts[index] = reader.int32()
                reader.skip(INT_SIZE + FILENAME_SIZE)
            }
            val maps =
                List(MAPS) { index ->
                    val count = counts[index]
                    val stops = mutableListOf<Pair<Int, Int>>()
                    if (count in 1..MAX_COLOURS) {
                        repeat(count) {
                            if (reader.has(3 * INT_SIZE)) {
                                stops += reader.int32().coerceIn(0, FULL) to reader.int32()
                                reader.skip(INT_SIZE)
                            }
                        }
                    }
                    Map(enabled[index], if (stops.isEmpty()) plain else bake(stops))
                }
            return ColorMapRenderer(Settings(key, blend, cycle, adjust, waitForFade, cycleSpeed.coerceAtLeast(1)), maps)
        }
    }
}
