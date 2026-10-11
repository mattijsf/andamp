// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import java.util.Random
import kotlin.math.cos
import kotlin.math.sqrt

// Water, Water Bump and Scatter: the Trans components that read the frame
// around each pixel. Transcribed from the vis_avs source (BSD 3-clause,
// Nullsoft); see NOTICE.md.

/**
 * Ripples: every pixel becomes half the sum of its four neighbors, less what
 * that pixel was a frame ago.
 *
 * Transcribed from vis_avs `r_water.cpp`. "A frame ago" is the frame as it
 * came into this component, before the ripple was applied. At an edge the
 * missing neighbors are left out, and a corner, which has two, is not halved.
 */
internal class WaterRenderer(
    private val enabled: Boolean,
) : AvsComponentRenderer {
    private var last = IntArray(0)
    private var incoming = IntArray(0)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        val w = frame.width
        val h = frame.height
        if (last.size != w * h) {
            last = IntArray(w * h)
            incoming = IntArray(w * h)
        }
        val out = frame.pixels
        out.copyInto(incoming)
        val from = incoming
        for (y in 0 until h) {
            for (x in 0 until w) {
                val at = y * w + x
                var red = 0
                var green = 0
                var blue = 0
                var neighbours = 0
                if (x > 0) {
                    val p = from[at - 1]
                    red += redOf(p)
                    green += greenOf(p)
                    blue += blueOf(p)
                    neighbours++
                }
                if (x < w - 1) {
                    val p = from[at + 1]
                    red += redOf(p)
                    green += greenOf(p)
                    blue += blueOf(p)
                    neighbours++
                }
                if (y > 0) {
                    val p = from[at - w]
                    red += redOf(p)
                    green += greenOf(p)
                    blue += blueOf(p)
                    neighbours++
                }
                if (y < h - 1) {
                    val p = from[at + w]
                    red += redOf(p)
                    green += greenOf(p)
                    blue += blueOf(p)
                    neighbours++
                }
                if (neighbours > CORNER) {
                    red /= 2
                    green /= 2
                    blue /= 2
                }
                val before = last[at]
                out[at] = pixelOf(red - redOf(before), green - greenOf(before), blue - blueOf(before))
            }
        }
        // the frame as it came in is what the next frame subtracts
        val spare = last
        last = incoming
        incoming = spare
    }

    companion object {
        /** A corner pixel has two neighbors. */
        private const val CORNER = 2

        fun read(body: ByteArray) = WaterRenderer(enabled = BodyReader(body).int32Or(1) != 0)
    }
}

/**
 * A water surface the frame is seen through: a drop falls on every beat, its
 * ripples spread, and each pixel is read from where the slope of the surface
 * points.
 *
 * Transcribed from vis_avs `r_waterbump.cpp`. The surface is a height per
 * pixel, kept on two pages that swap each frame. The outermost ring of pixels
 * has no slope and keeps the frame's own pixels.
 */
internal class WaterBumpRenderer(
    private val settings: Settings,
    private val random: Random = Random(),
) : AvsComponentRenderer {
    internal data class Settings(
        val enabled: Boolean = true,
        /** How fast the ripples die: each new height loses `1 / 2^density` of itself. */
        val density: Int = 6,
        val depth: Int = 600,
        val randomDrop: Boolean = false,
        /** 0 left, 1 center, 2 right. */
        val dropX: Int = 1,
        /** 0 top, 1 middle, 2 bottom. */
        val dropY: Int = 1,
        /** In pixels for a placed drop; in percent of the frame's long side for a random one. */
        val dropRadius: Int = 40,
    )

    private var pages = arrayOf(IntArray(0), IntArray(0))
    private var page = 0
    private var width = 0
    private var height = 0
    private var incoming = IntArray(0)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!settings.enabled) return
        val w = frame.width
        val h = frame.height
        if (w != width || h != height) {
            pages = arrayOf(IntArray(w * h), IntArray(w * h))
            incoming = IntArray(w * h)
            width = w
            height = h
        }
        if (state.beat) drop(w, h)

        val out = frame.pixels
        out.copyInto(incoming)
        val surface = pages[page]
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val at = y * w + x
                val dx = surface[at] - surface[at + 1]
                val dy = surface[at] - surface[at + w]
                val from = at + w * (dy shr SLOPE_SHIFT) + (dx shr SLOPE_SHIFT)
                out[at] = if (from in incoming.indices) incoming[from] else incoming[at]
            }
        }
        spread(pages[1 - page], pages[page])
        page = 1 - page
    }

    private fun drop(
        w: Int,
        h: Int,
    ) {
        if (settings.randomDrop) {
            sineBlob(-1, -1, settings.dropRadius * maxOf(w, h) / PERCENT, -settings.depth)
        } else {
            sineBlob(placed(settings.dropX, w), placed(settings.dropY, h), settings.dropRadius, -settings.depth)
        }
    }

    private fun placed(
        position: Int,
        size: Int,
    ) = when (position) {
        0 -> size / 4
        2 -> size * 3 / 4
        else -> size / 2
    }

    /** Adds a round dip to the current page. A negative [atX] or [atY] picks a place at random. */
    private fun sineBlob(
        atX: Int,
        atY: Int,
        radius: Int,
        blobHeight: Int,
    ) {
        if (radius <= 0) return
        val x = if (atX < 0) randomWithin(width, radius) else atX
        val y = if (atY < 0) randomWithin(height, radius) else atY
        val radiusSquared = radius * radius
        val length = (RIPPLE / radius.toFloat()).toDouble().let { it * it }
        var left = -radius
        var right = radius
        var top = -radius
        var bottom = radius
        if (x - radius < 1) left -= x - radius - 1
        if (y - radius < 1) top -= y - radius - 1
        if (x + radius > width - 1) right -= x + radius - width + 1
        if (y + radius > height - 1) bottom -= y + radius - height + 1
        val surface = pages[page]
        for (cy in top until bottom) {
            for (cx in left until right) {
                val square = cy * cy + cx * cx
                if (square < radiusSquared) {
                    val distance = sqrt(square * length)
                    surface[width * (cy + y) + cx + x] += ((cos(distance) + BLOB_BIAS) * blobHeight).toInt() shr BLOB_SHIFT
                }
            }
        }
    }

    /** A place for a blob of [radius] that keeps it inside [size]; the middle when it cannot fit. */
    private fun randomWithin(
        size: Int,
        radius: Int,
    ): Int {
        val room = size - 2 * radius - 1
        return if (room > 0) 1 + radius + random.nextInt(room) else size / 2
    }

    /** One step of the ripple: the eight neighbors' quarter-sum less the height two frames back, damped. */
    private fun spread(
        next: IntArray,
        current: IntArray,
    ) {
        val w = width
        val density = settings.density.coerceIn(0, MAX_SHIFT)
        for (y in 1 until height - 1) {
            for (x in 1 until w - 1) {
                val at = y * w + x
                val around =
                    current[at + w] + current[at - w] + current[at + 1] + current[at - 1] +
                        current[at - w - 1] + current[at - w + 1] + current[at + w - 1] + current[at + w + 1]
                val risen = (around shr 2) - next[at]
                next[at] = risen - (risen shr density)
            }
        }
    }

    companion object {
        private const val SLOPE_SHIFT = 3
        private const val PERCENT = 100
        private const val RIPPLE = 1024.0f
        private const val BLOB_BIAS = 0xffff
        private const val BLOB_SHIFT = 19
        private const val MAX_SHIFT = 31

        fun read(body: ByteArray): WaterBumpRenderer {
            val reader = BodyReader(body)
            val start = Settings()
            return WaterBumpRenderer(
                Settings(
                    enabled = reader.int32Or(1) != 0,
                    density = reader.int32Or(start.density),
                    depth = reader.int32Or(start.depth),
                    randomDrop = reader.int32Or(0) != 0,
                    dropX = reader.int32Or(start.dropX),
                    dropY = reader.int32Or(start.dropY),
                    dropRadius = reader.int32Or(start.dropRadius),
                ),
            )
        }
    }
}

/**
 * Every pixel is read from somewhere within four pixels of itself, picked
 * anew each frame.
 *
 * Transcribed from vis_avs `r_scat.cpp`. The top and bottom four rows are
 * left as they are, so no read leaves the frame.
 */
internal class ScatterRenderer(
    private val enabled: Boolean,
    private val random: Random = Random(),
) : AvsComponentRenderer {
    private var offsets = IntArray(0)
    private var offsetsFor = 0
    private var incoming = IntArray(0)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        val w = frame.width
        val h = frame.height
        if (!enabled || h <= 2 * EDGE_ROWS) return
        if (offsetsFor != w) {
            offsets = offsetsFor(w)
            offsetsFor = w
        }
        if (incoming.size != w * h) incoming = IntArray(w * h)
        val out = frame.pixels
        out.copyInto(incoming)
        for (at in w * EDGE_ROWS until w * (h - EDGE_ROWS)) {
            out[at] = incoming[at + offsets[random.nextInt(OFFSETS)]]
        }
    }

    companion object {
        private const val EDGE_ROWS = 4
        private const val OFFSETS = 512
        private const val SPAN = 8

        /** The 64 places a pixel can come from, repeated to fill the table: -3..3 each way, zero twice as likely. */
        internal fun offsetsFor(width: Int) =
            IntArray(OFFSETS) { index ->
                var across = index % SPAN - SPAN / 2
                var down = index / SPAN % SPAN - SPAN / 2
                if (across < 0) across++
                if (down < 0) down++
                width * down + across
            }

        fun read(body: ByteArray) = ScatterRenderer(enabled = BodyReader(body).int32Or(1) != 0)
    }
}
