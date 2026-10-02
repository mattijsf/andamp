// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.abs

/**
 * Putting marks on a frame: a dot, and a line between two.
 *
 * The line is transcribed from vis_avs `linedraw.cpp` (BSD; see NOTICE.md),
 * quirks included: a thick line is centered on the ideal one by `width / 2` and
 * painted as one perpendicular run per major-axis step, so no pixel is blended
 * twice; the far endpoint's column (or row) is left undrawn; a zero-length
 * line draws nothing. Everything clips: a point off the edge is not drawn.
 */
object AvsDraw {
    fun dot(
        frame: AvsFrame,
        x: Int,
        y: Int,
        colour: Int,
        mode: AvsBlendMode = AvsBlendMode.REPLACE,
        adjust: Int = 255,
    ) {
        if (x !in 0 until frame.width || y !in 0 until frame.height) return
        frame[x, y] = if (mode == AvsBlendMode.REPLACE) colour else AvsBlend.pixel(mode, colour, frame[x, y], adjust)
    }

    /**
     * vis_avs `linedraw.cpp` `line()`: four cases (vertical, horizontal, and
     * the two Bresenham majors), each painting `thickness`-long perpendicular
     * runs offset by `thickness / 2`.
     */
    @Suppress("LongParameterList") // the original's signature: two endpoints, color, width, and the blend
    fun line(
        frame: AvsFrame,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        colour: Int,
        thickness: Int = 1,
        mode: AvsBlendMode = AvsBlendMode.REPLACE,
        adjust: Int = 255,
    ) {
        // the original clamps: if (lw < 1) lw = 1; else if (lw > 255) lw = 255;
        val lw = thickness.coerceIn(1, MAX_LINE_WIDTH)
        // clip before walking: a preset's own math can put an endpoint at a
        // million (a 3D projection dividing by nearly zero). The original's
        // edge clamps (transcribed below) bound the walk, and this keeps their
        // integer products from overflowing. On-frame endpoints pass through
        // unchanged.
        val clipped = clip(fromX.toDouble(), fromY.toDouble(), toX.toDouble(), toY.toDouble(), frame, lw) ?: return
        val x1 = clipped[0]
        val y1 = clipped[1]
        val x2 = clipped[2]
        val y2 = clipped[3]
        val dx = abs(x2 - x1)
        val dy = abs(y2 - y1)
        when {
            dx == 0 -> verticalLine(frame, x1, y1, y2, colour, lw, mode, adjust)
            dy == 0 -> horizontalLine(frame, y1, x1, x2, colour, lw, mode, adjust)
            dy <= dx -> xMajorLine(frame, x1, y1, x2, y2, colour, lw, mode, adjust)
            else -> yMajorLine(frame, x1, y1, x2, y2, colour, lw, mode, adjust)
        }
    }

    /** In-bounds by construction; every caller has clamped its run first. */
    private fun plot(
        frame: AvsFrame,
        x: Int,
        y: Int,
        colour: Int,
        mode: AvsBlendMode,
        adjust: Int,
    ) {
        frame[x, y] = if (mode == AvsBlendMode.REPLACE) colour else AvsBlend.pixel(mode, colour, frame[x, y], adjust)
    }

    /** The `!dx` fast path: rows `[min, max)`, the span centered by `lw / 2`. */
    @Suppress("LongParameterList") // a fixed axis, a range, and the blend; grouping would invent a type
    private fun verticalLine(
        frame: AvsFrame,
        x: Int,
        yA: Int,
        yB: Int,
        colour: Int,
        width: Int,
        mode: AvsBlendMode,
        adjust: Int,
    ) {
        var x1 = x - width / 2
        var lw = width
        if (x1 + lw < 0 || x1 >= frame.width) return
        var d = maxOf(minOf(yA, yB), 0)
        val ye = minOf(maxOf(yA, yB), frame.height - 1)
        if (x1 < 0) {
            lw += x1
            x1 = 0
        }
        if (x1 + lw >= frame.width) lw = frame.width - x1
        if (lw <= 0) return
        while (d < ye) {
            for (i in 0 until lw) {
                plot(frame, x1 + i, d, colour, mode, adjust)
            }
            d++
        }
    }

    /** The `y1 == y2` fast path: columns `[min, max)` where max is already clamped to the last column. */
    @Suppress("LongParameterList") // a fixed axis, a range, and the blend; grouping would invent a type
    private fun horizontalLine(
        frame: AvsFrame,
        y: Int,
        xA: Int,
        xB: Int,
        colour: Int,
        width: Int,
        mode: AvsBlendMode,
        adjust: Int,
    ) {
        var y1 = y - width / 2
        var lw = width
        if (y1 + lw < 0 || y1 >= frame.height) return
        val d = maxOf(minOf(xA, xB), 0)
        val xe = minOf(maxOf(xA, xB), frame.width - 1)
        if (y1 < 0) {
            lw += y1
            y1 = 0
        }
        if (y1 + lw >= frame.height) lw = frame.height - y1
        for (row in 0 until lw) {
            var lt = d
            while (lt < xe) {
                plot(frame, lt, y1 + row, colour, mode, adjust)
                lt++
            }
        }
    }

    /** `dy <= dx`: one vertical `lw`-tall run per column, the walked y offset by `lw / 2`. */
    @Suppress("LongParameterList") // the original's loop signature; grouping the endpoints would invent a type
    private fun xMajorLine(
        frame: AvsFrame,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        colour: Int,
        width: Int,
        mode: AvsBlendMode,
        adjust: Int,
    ) {
        var x1 = fromX
        var y1 = fromY
        var x2 = toX
        var y2 = toY
        if (x2 < x1) {
            x1 = toX
            y1 = toY
            x2 = fromX
            y2 = fromY
        }
        val dx = x2 - x1
        val dy = abs(y2 - y1)
        val yIncr = if (y2 > y1) 1 else -1
        y1 -= width / 2
        var d = dy + dy - dx
        val eIncr = dy + dy
        val neIncr = d - dx
        if (x2 < 0 || x1 >= frame.width) return
        if (x1 < 0) {
            // the original's entry clip: advance y along the slope and leave
            // the error term unchanged
            y1 += (yIncr * -x1 * dy) / dx
            x1 = 0
        }
        if (x2 > frame.width) x2 = frame.width
        while (x1 < x2) {
            var yp = maxOf(y1, 0)
            val ype = minOf(y1 + width, frame.height)
            while (yp < ype) {
                plot(frame, x1, yp, colour, mode, adjust)
                yp++
            }
            if (d < 0) {
                d += eIncr
            } else {
                d += neIncr
                y1 += yIncr
            }
            x1++
        }
    }

    /** `dy > dx`: one horizontal `lw`-wide run per row, the walked x offset by `lw / 2`. */
    @Suppress("LongParameterList") // the original's loop signature; grouping the endpoints would invent a type
    private fun yMajorLine(
        frame: AvsFrame,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        colour: Int,
        width: Int,
        mode: AvsBlendMode,
        adjust: Int,
    ) {
        var x1 = fromX
        var y1 = fromY
        var x2 = toX
        var y2 = toY
        if (y2 < y1) {
            x1 = toX
            y1 = toY
            x2 = fromX
            y2 = fromY
        }
        val dx = abs(x2 - x1)
        val dy = y2 - y1
        val xIncr = if (x2 > x1) 1 else -1
        var d = dx + dx - dy
        val eIncr = dx + dx
        val neIncr = d - dy
        x1 -= width / 2
        if (y2 < 0 || y1 >= frame.height) return
        if (y1 < 0) {
            x1 += (xIncr * -y1 * dx) / dy
            y1 = 0
        }
        if (y2 > frame.height) y2 = frame.height
        while (y1 < y2) {
            var xp = maxOf(x1, 0)
            val xpe = minOf(x1 + width, frame.width)
            while (xp < xpe) {
                plot(frame, xp, y1, colour, mode, adjust)
                xp++
            }
            if (d < 0) {
                d += eIncr
            } else {
                d += neIncr
                x1 += xIncr
            }
            y1++
        }
    }

    /**
     * Liang-Barsky against the frame (with a margin for the line width), or
     * null when the whole segment misses. Endpoints land on the walkable ints.
     */
    @Suppress("ReturnCount") // four rejection sides; folding them hides the shape
    private fun clip(
        fromX: Double,
        fromY: Double,
        toX: Double,
        toY: Double,
        frame: AvsFrame,
        lineWidth: Int,
    ): IntArray? {
        val margin = lineWidth.toDouble()
        val minX = -margin
        val minY = -margin
        val maxX = frame.width - 1 + margin
        val maxY = frame.height - 1 + margin
        val dx = toX - fromX
        val dy = toY - fromY
        var t0 = 0.0
        var t1 = 1.0

        for (side in 0 until SIDES) {
            val (p, q) =
                when (side) {
                    0 -> -dx to fromX - minX
                    1 -> dx to maxX - fromX
                    2 -> -dy to fromY - minY
                    else -> dy to maxY - fromY
                }
            when {
                p == 0.0 && q < 0 -> {
                    return null
                }

                p < 0.0 -> {
                    val t = q / p
                    if (t > t1) return null
                    if (t > t0) t0 = t
                }

                p > 0.0 -> {
                    val t = q / p
                    if (t < t0) return null
                    if (t < t1) t1 = t
                }
            }
        }
        return intArrayOf(
            (fromX + t0 * dx).toInt(),
            (fromY + t0 * dy).toInt(),
            (fromX + t1 * dx).toInt(),
            (fromY + t1 * dy).toInt(),
        )
    }

    /** The original's clamp: `if (lw > 255) lw = 255`. */
    private const val MAX_LINE_WIDTH = 255
    private const val SIDES = 4
}
