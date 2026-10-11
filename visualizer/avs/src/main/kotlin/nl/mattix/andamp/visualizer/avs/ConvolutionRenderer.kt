// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.abs

/**
 * A 7 by 7 convolution: every pixel becomes a weighted sum of the pixels
 * around it.
 *
 * After vis_avs `e_convolution.cpp` (BSD; see NOTICE.md), which is Tom
 * Holden's Convolution Filter plug-in. That source generates machine code;
 * this follows the arithmetic the generated code performs. Each channel is
 * summed over the kernel, the bias is added, and the total is divided by the
 * scale. A result outside 0 to 255 is clamped, or wraps around when the
 * preset asks for that, and "absolute" takes the size of a negative sum. A
 * neighbor past an edge is the pixel at the edge. With two passes the kernel
 * is applied a second time turned a quarter, and the two sums are added
 * before the division, which is how an edge filter finds edges both ways.
 */
internal class ConvolutionRenderer(
    private val settings: Settings,
) : AvsComponentRenderer {
    internal data class Settings(
        val enabled: Boolean = true,
        val wrap: Boolean = false,
        val absolute: Boolean = false,
        val twoPass: Boolean = false,
        /** Row by row, 49 weights. A new filter has a 1 in the middle and copies the frame. */
        val kernel: List<Int> = List(SIZE) { if (it == SIZE / 2) 1 else 0 },
        val bias: Int = 0,
        val scale: Int = 1,
    )

    /** The weights that are not zero, as (column offset, row offset, weight), for each pass. */
    private val passes: List<List<IntArray>> =
        buildList {
            add(taps(turned = false))
            if (settings.twoPass) add(taps(turned = true))
        }
    private val scale = if (settings.scale == 0) 1 else settings.scale
    private var incoming = IntArray(0)

    private fun taps(turned: Boolean): List<IntArray> =
        settings.kernel.mapIndexedNotNull { index, weight ->
            if (weight == 0) return@mapIndexedNotNull null
            val column = index % DIM
            val row = index / DIM
            if (turned) {
                intArrayOf(
                    DIM - 1 - row - REACH,
                    column - REACH,
                    weight,
                )
            } else {
                intArrayOf(column - REACH, row - REACH, weight)
            }
        }

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!settings.enabled) return
        val w = frame.width
        val h = frame.height
        val out = frame.pixels
        if (incoming.size != out.size) incoming = IntArray(out.size)
        out.copyInto(incoming)
        for (y in 0 until h) {
            for (x in 0 until w) {
                sums.fill(0)
                for (pass in passes) add(pass, x, y, w, h)
                out[y * w + x] =
                    AvsFrame.OPAQUE or (level(sums[0]) shl RED_SHIFT) or (level(sums[1]) shl GREEN_SHIFT) or level(sums[2])
            }
        }
    }

    /** Red, green and blue, summed over the passes for the pixel being worked on. */
    private val sums = IntArray(CHANNELS)

    /** One pass of the kernel at ([x], [y]), added to [sums]. */
    private fun add(
        pass: List<IntArray>,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        var red = settings.bias
        var green = settings.bias
        var blue = settings.bias
        for (tap in pass) {
            val from = incoming[(y + tap[1]).coerceIn(0, h - 1) * w + (x + tap[0]).coerceIn(0, w - 1)]
            red += redOf(from) * tap[2]
            green += greenOf(from) * tap[2]
            blue += blueOf(from) * tap[2]
        }
        sums[0] += if (settings.absolute) abs(red) else red
        sums[1] += if (settings.absolute) abs(green) else green
        sums[2] += if (settings.absolute) abs(blue) else blue
    }

    private fun level(sum: Int): Int {
        val scaled = sum / scale
        return if (settings.wrap) scaled and FULL else scaled.coerceIn(0, FULL)
    }

    companion object {
        private const val DIM = 7
        private const val SIZE = DIM * DIM
        private const val REACH = DIM / 2
        private const val FULL = 255
        private const val CHANNELS = 3
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8

        /** Four flags, the 49 weights, the bias and the scale; a file name may follow. */
        fun read(body: ByteArray): ConvolutionRenderer {
            val reader = BodyReader(body)
            val start = Settings()
            val enabled = reader.int32Or(1) == 1
            val wrap = reader.int32Or(0) == 1
            val absolute = reader.int32Or(0) == 1
            val twoPass = reader.int32Or(0) == 1
            val kernel = List(SIZE) { reader.int32Or(start.kernel[it]) }
            val bias = reader.int32Or(0)
            val scale = reader.int32Or(1)
            return ConvolutionRenderer(Settings(enabled, wrap, absolute, twoPass, kernel, bias, scale))
        }
    }
}
