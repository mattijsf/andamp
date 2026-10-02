// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where each pixel of the next frame comes from in this one.
 *
 * Used by both Movement and Dynamic Movement: a piece of ns-eel is evaluated at
 * each vertex of a grid, producing the source coordinate for that vertex, and
 * the pixels between vertices are interpolated. That is how AVS's Dynamic
 * Movement works; plain Movement in AVS evaluates per pixel into a static
 * table, so for it the grid is an approximation: sharp discontinuities (the
 * kaleida fold, the atan2 seam) smear slightly where AVS keeps them hard.
 *
 * The coordinate conventions are transcribed from vis_avs (BSD; see NOTICE.md),
 * `e_dynamicmovement.cpp` / `e_movement.cpp`:
 *
 * - `x`, `y` are per-axis, -1..1 across the frame.
 * - `d` is isotropic: the distance in pixels over the half-diagonal
 *   `sqrt(w² + h²) / 2`, so 1.0 sits at the corner and circles stay circles
 *   on non-square frames.
 * - `r` is `atan2` of the pixel offsets plus pi/2. AVS hands scripts the
 *   offset convention and takes it back off when converting polar results to
 *   pixels, which is why the built-in scripts say `r - pi/2` where the native
 *   effects say `r`.
 */
class WarpMesh(
    private val columns: Int,
    private val rows: Int,
) {
    /**
     * One vertex's answer: where to read from, and how strongly to blend.
     *
     * A single instance is reused across the whole evaluation so a per-frame
     * mesh rebuild allocates nothing per vertex.
     */
    class Vertex internal constructor() {
        /** Source position, per-axis -1..1. */
        var x = 0.0
        var y = 0.0

        /** Blend weight 0..1 for [sample]'s blended mode. AVS's per-frame default. */
        var alpha = DEFAULT_ALPHA

        private var width = 0
        private var height = 0

        internal fun frame(
            width: Int,
            height: Int,
        ) {
            this.width = width
            this.height = height
        }

        /**
         * A polar answer back to cartesian, in this frame's geometry.
         *
         * Transcribed from the polar branch of `e_dynamicmovement.cpp`:
         * `d` scales back up by the half-diagonal, `r` gives its pi/2 offset
         * back, and the pixel position renormalizes per axis.
         */
        fun fromPolar(
            d: Double,
            r: Double,
        ) {
            val distance = d * maxDistance(width, height)
            val angle = r - HALF_PI
            x = cos(angle) * distance / (width * 0.5)
            y = sin(angle) * distance / (height * 0.5)
        }
    }

    /** Source x, y and alpha per vertex, interleaved. */
    private val vertices = DoubleArray((columns + 1) * (rows + 1) * STRIDE)
    private val vertex = Vertex()
    private var width = 0
    private var height = 0
    private var wrap = false

    /**
     * Runs [at] for every vertex.
     *
     * @param at given the vertex's own x, y, distance and angle (in the
     *   conventions above), writes where that vertex reads from into [Vertex],
     *   in cartesian, or through [Vertex.fromPolar] for polar code.
     */
    fun evaluate(
        width: Int,
        height: Int,
        wrap: Boolean,
        at: (x: Double, y: Double, d: Double, r: Double, out: Vertex) -> Unit,
    ) {
        this.width = width
        this.height = height
        this.wrap = wrap
        vertex.frame(width, height)
        val invMaxD = 1.0 / maxDistance(width, height)
        // without wrap AVS clamps each vertex to the frame before interpolating,
        // so an answer far outside pulls no further than the edge pixel
        val maxX = 1.0 - 2.0 / width
        val maxY = 1.0 - 2.0 / height
        var i = 0
        for (row in 0..rows) {
            val y = row.toDouble() / rows * 2.0 - 1.0
            val yd = y * height * 0.5
            for (column in 0..columns) {
                val x = column.toDouble() / columns * 2.0 - 1.0
                val xd = x * width * 0.5
                val d = sqrt(xd * xd + yd * yd) * invMaxD
                val r = atan2(yd, xd) + HALF_PI
                vertex.x = x
                vertex.y = y
                at(x, y, d, r, vertex)
                vertices[i++] = if (wrap) vertex.x else vertex.x.coerceIn(-1.0, maxX)
                vertices[i++] = if (wrap) vertex.y else vertex.y.coerceIn(-1.0, maxY)
                vertices[i++] = vertex.alpha.coerceIn(0.0, 1.0)
            }
        }
    }

    /**
     * Reads [source] through the mesh into [destination].
     *
     * With [blend] the warped pixel mixes over what [destination] already
     * holds at each vertex's own alpha, interpolated across cells (Dynamic
     * Movement's adjustable blend). Without it the warped pixel replaces.
     *
     * The source pixel is read nearest-neighbor. AVS's bilinear option is not
     * implemented, and a preset that asks for it gets this.
     */
    fun sample(
        source: AvsFrame,
        destination: AvsFrame,
        blend: Boolean = false,
    ) {
        // per-pixel work is lookups and arithmetic only: the column and its
        // fraction depend on x alone, so they are computed once per width,
        // and nothing is allocated per pixel
        cacheColumns(destination.width)
        for (y in 0 until destination.height) {
            // the destination lattice is AVS's: vertex k sits at row k*h/rows,
            // so an identity mesh lands back on its own pixel
            val gridY = y.toDouble() / destination.height * rows
            val row = gridY.toInt().coerceIn(0, rows - 1)
            val fy = (gridY - row).coerceIn(0.0, 1.0)
            for (x in 0 until destination.width) {
                val column = columnCache[x]
                val fx = fxCache[x]

                val topLeft = index(column, row)
                val topRight = index(column + 1, row)
                val bottomLeft = index(column, row + 1)
                val bottomRight = index(column + 1, row + 1)

                val sx =
                    mix(
                        mix(vertices[topLeft], vertices[topRight], fx),
                        mix(vertices[bottomLeft], vertices[bottomRight], fx),
                        fy,
                    )
                val sy =
                    mix(
                        mix(vertices[topLeft + 1], vertices[topRight + 1], fx),
                        mix(vertices[bottomLeft + 1], vertices[bottomRight + 1], fx),
                        fy,
                    )
                val warped = read(source, sx, sy)
                destination[x, y] =
                    if (blend) {
                        val alpha =
                            mix(
                                mix(vertices[topLeft + 2], vertices[topRight + 2], fx),
                                mix(vertices[bottomLeft + 2], vertices[bottomRight + 2], fx),
                                fy,
                            )
                        AvsBlend.pixel(AvsBlendMode.ADJUSTABLE, warped, destination[x, y], (alpha * FULL).toInt())
                    } else {
                        warped
                    }
            }
        }
    }

    private var columnCache = IntArray(0)
    private var fxCache = DoubleArray(0)

    private fun cacheColumns(width: Int) {
        if (columnCache.size == width) return
        columnCache = IntArray(width)
        fxCache = DoubleArray(width)
        for (x in 0 until width) {
            val gridX = x.toDouble() / width * columns
            columnCache[x] = gridX.toInt().coerceIn(0, columns - 1)
            fxCache[x] = (gridX - columnCache[x]).coerceIn(0.0, 1.0)
        }
    }

    private fun index(
        column: Int,
        row: Int,
    ) = (row * (columns + 1) + column) * STRIDE

    private fun mix(
        from: Double,
        to: Double,
        by: Double,
    ) = from + (to - from) * by

    /**
     * -1..1 back to a pixel, wrapped around the frame or clamped to its edge.
     *
     * The +0.5 is Movement's rounding (`e_movement.cpp` adds it before the
     * cast); Dynamic Movement's fixed-point pipeline truncates instead, a
     * sub-pixel difference this shared path does not reproduce. Wrapping is
     * modulo the frame, Movement's period; Dynamic Movement tiles one pixel
     * shorter, which is also not reproduced.
     */
    private fun read(
        source: AvsFrame,
        x: Double,
        y: Double,
    ): Int {
        val px = ((x + 1.0) * source.width * 0.5 + 0.5).toInt()
        val py = ((y + 1.0) * source.height * 0.5 + 0.5).toInt()
        val sx = if (wrap) px.mod(source.width) else px.coerceIn(0, source.width - 1)
        val sy = if (wrap) py.mod(source.height) else py.coerceIn(0, source.height - 1)
        return source[sx, sy]
    }

    companion object {
        private const val STRIDE = 3
        private const val FULL = 255
        private const val HALF_PI = PI * 0.5
        internal const val DEFAULT_ALPHA = 0.5

        /** AVS's `max_d`: half the frame diagonal, in pixels. */
        fun maxDistance(
            width: Int,
            height: Int,
        ): Double = sqrt((width.toDouble() * width + height.toDouble() * height)) * 0.5
    }
}
