// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.roundToInt

/**
 * The depth every Triangle of a preset draws against: how near the nearest
 * triangle drawn so far is at each pixel. A larger value is nearer.
 */
internal class TriangleDepth {
    private var depth = LongArray(0)
    private var width = 0
    private var height = 0

    /** The buffer for a frame of this size, emptied when [clear] is set or the size changed. */
    fun forFrame(
        w: Int,
        h: Int,
        clear: Boolean,
    ): LongArray {
        if (clear || w != width || h != height) {
            depth = LongArray(w * h)
            width = w
            height = h
        }
        return depth
    }
}

/**
 * Filled triangles the preset's code places, one per run of its per-triangle
 * code.
 *
 * Transcribed from vis_avs `e_triangle.cpp` (BSD; see NOTICE.md), a rewrite of
 * TomyLobo's Triangle plug-in. The code sets `n`, how many triangles, and per
 * triangle the corners `x1,y1` to `x3,y3` in -1..1 and the color `red1`,
 * `green1`, `blue1`. A triangle is one flat color: the second and third
 * corner's colors are variables the code may set and the renderer does not
 * read. With `zbuf` set a triangle is drawn only where its `z1` is at least
 * as near as what is there already, and `zbclear` empties that record.
 * Without `zbuf` it is drawn with the render mode.
 */
internal class TriangleRenderer(
    init: String,
    perFrame: String,
    onBeat: String,
    perTriangle: String,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    private val width = eel.variable("w")
    private val height = eel.variable("h")
    private val count = eel.variable("n")
    private val index = eel.variable("i")
    private val skip = eel.variable("skip")
    private val xs = listOf("x1", "x2", "x3").map(eel::variable)
    private val ys = listOf("y1", "y2", "y3").map(eel::variable)
    private val colours =
        listOf(
            "red1",
            "green1",
            "blue1",
            "red2",
            "green2",
            "blue2",
            "red3",
            "green3",
            "blue3",
        ).map(eel::variable)
    private val z = eel.variable("z1")
    private val useDepth = eel.variable("zbuf")
    private val clearDepth = eel.variable("zbclear")

    private val initCode = compile(init)
    private val frameCode = compile(perFrame)
    private val beatCode = compile(onBeat)
    private val triangleCode = compile(perTriangle)

    override val errors: List<String> =
        buildList {
            if (MovementEffect.hasCode(init) && initCode == null) add("init")
            if (MovementEffect.hasCode(perFrame) && frameCode == null) add("per frame")
            if (MovementEffect.hasCode(onBeat) && beatCode == null) add("on beat")
            if (MovementEffect.hasCode(perTriangle) && triangleCode == null) add("per triangle")
        }

    private var started = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!started) {
            started = true
            count.value = 0.0
            initCode?.run()
        }
        width.value = frame.width.toDouble()
        height.value = frame.height.toDouble()
        (xs + ys).forEach { it.value = 0.0 }
        colours.forEach { it.value = 1.0 }
        frameCode?.run()
        if (state.beat) beatCode?.run()

        val depth = state.triangleDepth.forFrame(frame.width, frame.height, clear = clearDepth.value != 0.0)
        val triangles = count.value.roundToInt().coerceAtMost(MAX_TRIANGLES)
        if (triangles <= 0) return
        val step = if (triangles > 1) 1.0 / (triangles - 1.0) else 1.0
        val halfWidth = (frame.width - 1) / 2.0
        val halfHeight = (frame.height - 1) / 2.0
        var at = 0.0
        index.value = at
        repeat(triangles) {
            skip.value = 0.0
            triangleCode?.run()
            if (skip.value == 0.0) {
                val corners =
                    List(
                        CORNERS,
                    ) { intArrayOf(((xs[it].value + 1.0) * halfWidth).toInt(), ((ys[it].value + 1.0) * halfHeight).toInt()) }
                val colour = pixelOf(level(colours[0].value), level(colours[1].value), level(colours[2].value))
                fill(frame, state, corners.sortedBy { it[1] }, colour, if (useDepth.value != 0.0) depth else null)
                at += step
                index.value = at
            }
        }
    }

    /**
     * The rows between the long edge and the two short ones: first from the
     * top corner down to the middle one, then on to the bottom. The top
     * corner's own row and the bottom corner's are left out.
     */
    private fun fill(
        frame: AvsFrame,
        state: AvsRenderState,
        corners: List<IntArray>,
        colour: Int,
        depth: LongArray?,
    ) {
        val (top, middle, bottom) = corners
        val h = frame.height
        var y = top[1].coerceIn(0, h)
        if (y == top[1]) y++
        val middleRow = middle[1].coerceIn(-1, h - 1)
        val lastRow = bottom[1].coerceIn(-1, h)
        val nearness = nearness(z.value)
        while (y <= middleRow) {
            row(frame, state, y, edge(top, middle, y), edge(top, bottom, y), colour, depth, nearness)
            y++
        }
        while (y < lastRow) {
            row(frame, state, y, edge(middle, bottom, y), edge(top, bottom, y), colour, depth, nearness)
            y++
        }
    }

    /** Where the edge from [from] to [to] crosses row [y]. */
    private fun edge(
        from: IntArray,
        to: IntArray,
        y: Int,
    ): Int = if (to[1] == from[1]) from[0] else (to[0] - from[0]) * (y - from[1]) / (to[1] - from[1]) + from[0]

    @Suppress("LongParameterList") // one row of one triangle: where, what color, and what it is drawn against
    private fun row(
        frame: AvsFrame,
        state: AvsRenderState,
        y: Int,
        one: Int,
        other: Int,
        colour: Int,
        depth: LongArray?,
        nearness: Long,
    ) {
        if (y !in 0 until frame.height) return
        val w = frame.width
        val from = minOf(one, other)
        val to = maxOf(one, other)
        if (to < 0 || from >= w) return
        val first = from.coerceAtLeast(0)
        val end = to.coerceAtMost(w - 1)
        for (x in first until end) {
            val at = y * w + x
            if (depth == null) {
                AvsDraw.dot(frame, x, y, colour, state.renderBlend, state.renderAdjust)
            } else if (nearness >= depth[at]) {
                depth[at] = nearness
                frame.pixels[at] = colour
            }
        }
    }

    private fun level(value: Double) = (value * FULL).coerceIn(0.0, FULL.toDouble()).toInt()

    /** `z1` in the fixed point the depth buffer holds. */
    private fun nearness(depth: Double): Long =
        when {
            depth > MAX_Z -> MAX_Z.toLong() * Z_RESOLUTION
            depth > 0 -> (depth * Z_RESOLUTION).toLong()
            else -> 0
        }

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    companion object {
        private const val CORNERS = 3
        private const val FULL = 255
        private const val MAX_Z = 40_000
        private const val Z_RESOLUTION = 100_000

        /** No preset needs more, and a script that sets `n` wildly must not hang the frame. */
        private const val MAX_TRIANGLES = 100_000

        /** The body is four pieces of code, each ending in a null: init, per frame, on beat, per triangle. */
        internal fun parse(body: ByteArray): List<String> {
            val reader = BodyReader(body)
            return List(CORNERS + 1) { reader.cString() }
        }

        fun read(
            body: ByteArray,
            eel: Eel,
        ): TriangleRenderer {
            val code = parse(body)
            return TriangleRenderer(init = code[0], perFrame = code[1], onBeat = code[2], perTriangle = code[3], eel = eel)
        }
    }
}
