// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The mesh on its own, driven by plain Kotlin instead of ns-eel.
 *
 * Movement and Dynamic Movement share the mesh, so it is tested here without
 * the evaluator, where a failure is about coordinates, interpolation and
 * sampling. The coordinate conventions under test are vis_avs's own: isotropic
 * `d`, `r` offset by half pi, per-vertex clamping and alpha.
 */
class WarpMeshTest {
    @Test
    fun `a mesh that reads where it is copies the frame`() {
        val source = coded(SIZE, SIZE)
        val destination = AvsFrame(SIZE, SIZE)
        val mesh = WarpMesh(GRID, GRID)

        mesh.evaluate(SIZE, SIZE, wrap = false) { x, y, _, _, out ->
            out.x = x
            out.y = y
        }
        mesh.sample(source, destination)

        assertInteriorEquals(source, destination)
    }

    /**
     * AVS clamps the vertex past the edge to the last pixel before it
     * interpolates, so the far edge of an identity warp squeezes by one.
     */
    @Test
    fun `the identity's far edge squeezes one pixel`() {
        val source = coded(SIZE, SIZE)
        val destination = AvsFrame(SIZE, SIZE)
        val mesh = WarpMesh(GRID, GRID)

        mesh.evaluate(SIZE, SIZE, wrap = false) { x, y, _, _, out ->
            out.x = x
            out.y = y
        }
        mesh.sample(source, destination)

        assertEquals(source[SIZE - 2, 32], destination[SIZE - 1, 32])
    }

    @Test
    fun `shifting where it reads from moves what is drawn`() {
        val source = AvsFrame(SIZE, SIZE)
        source[20, 32] = WHITE
        val destination = AvsFrame(SIZE, SIZE)
        val mesh = WarpMesh(GRID, GRID)

        // read from a quarter-frame to the right of each pixel:
        // 0.5 in -1..1 is 16.25 pixels on a 65-wide frame
        mesh.evaluate(SIZE, SIZE, wrap = false) { x, y, _, _, out ->
            out.x = x + 0.5
            out.y = y
        }
        mesh.sample(source, destination)

        assertEquals(WHITE, destination[4, 32])
    }

    /**
     * At the right-edge middle vertex the mesh hands out `r = atan2 + pi/2`
     * and a `d` normalized by the half-diagonal in pixels, which is under one
     * there, where per-axis normalization would say one.
     */
    @Test
    fun `r carries AVS's half-pi offset and d is isotropic over the half-diagonal`() {
        val mesh = WarpMesh(GRID, GRID)
        var seenD = Double.NaN
        var seenR = Double.NaN

        mesh.evaluate(WIDE, TALL, wrap = false) { x, y, d, r, out ->
            if (x == 1.0 && y == 0.0) {
                seenD = d
                seenR = r
            }
            out.x = x
            out.y = y
        }

        val halfDiagonal = sqrt(WIDE.toDouble() * WIDE + TALL.toDouble() * TALL) / 2.0
        assertEquals(PI / 2, seenR, 1e-9)
        assertEquals(WIDE / 2.0 / halfDiagonal, seenD, 1e-9)
        assertTrue("the isotropic d is below 1.0 here", seenD < 1.0)
    }

    /**
     * [WarpMesh.Vertex.fromPolar] undoes what evaluate hands out, so untouched
     * polar code is the identity, also on a non-square frame, where a per-axis
     * d would bend it.
     */
    @Test
    fun `an untouched polar pair round-trips to the identity on a non-square frame`() {
        val source = coded(WIDE, TALL)
        val destination = AvsFrame(WIDE, TALL)
        val mesh = WarpMesh(GRID, GRID)

        mesh.evaluate(WIDE, TALL, wrap = false) { _, _, d, r, out -> out.fromPolar(d, r) }
        mesh.sample(source, destination)

        assertInteriorEquals(source, destination)
    }

    @Test
    fun `a rotation in polar coordinates moves the frame`() {
        val source = spotted()
        val destination = AvsFrame(SIZE, SIZE)
        val mesh = WarpMesh(GRID, GRID)

        mesh.evaluate(SIZE, SIZE, wrap = false) { _, _, d, r, out -> out.fromPolar(d, r + ROTATION) }
        mesh.sample(source, destination)

        assertNotEquals("the rotation changes the frame", source.pixels.toList(), destination.pixels.toList())
    }

    @Test
    fun `wrapping and clamping disagree at the edge`() {
        val source = AvsFrame(SIZE, SIZE).also { it[0, SIZE / 2] = WHITE }
        val wrapped = AvsFrame(SIZE, SIZE)
        val clamped = AvsFrame(SIZE, SIZE)
        val wrappingMesh = WarpMesh(GRID, GRID)
        val clampingMesh = WarpMesh(GRID, GRID)

        wrappingMesh.evaluate(SIZE, SIZE, wrap = true) { x, y, _, _, out ->
            out.x = x + 1.5
            out.y = y
        }
        clampingMesh.evaluate(SIZE, SIZE, wrap = false) { x, y, _, _, out ->
            out.x = x + 1.5
            out.y = y
        }
        wrappingMesh.sample(source, wrapped)
        clampingMesh.sample(source, clamped)

        assertNotEquals(wrapped.pixels.toList(), clamped.pixels.toList())
    }

    /** Dynamic Movement's blend: warped over original at the vertex's own alpha. */
    @Test
    fun `blended sampling mixes at the per-vertex alpha`() {
        val source = AvsFrame(SIZE, SIZE).also { it.pixels.fill(WHITE) }
        val destination = AvsFrame(SIZE, SIZE)
        val mesh = WarpMesh(GRID, GRID)

        mesh.evaluate(SIZE, SIZE, wrap = false) { x, y, _, _, out ->
            out.x = x
            out.y = y
            out.alpha = 0.25
        }
        mesh.sample(source, destination, blend = true)

        // a quarter of white over black: 255 * 63 / 255 per channel
        assertEquals(0xFF3F3F3F.toInt(), destination[10, 10])
    }

    @Test
    fun `alpha clamps to one`() {
        val source = AvsFrame(SIZE, SIZE).also { it.pixels.fill(WHITE) }
        val destination = AvsFrame(SIZE, SIZE)
        val mesh = WarpMesh(GRID, GRID)

        mesh.evaluate(SIZE, SIZE, wrap = false) { x, y, _, _, out ->
            out.x = x
            out.y = y
            out.alpha = 5.0
        }
        mesh.sample(source, destination, blend = true)

        assertEquals(WHITE, destination[10, 10])
    }

    /** Equality away from the right and bottom edge cells, where the vertex clamp squeezes. */
    private fun assertInteriorEquals(
        source: AvsFrame,
        destination: AvsFrame,
    ) {
        val safeX = (GRID - 1) * source.width / GRID - 1
        val safeY = (GRID - 1) * source.height / GRID - 1
        for (y in 0 until safeY) {
            for (x in 0 until safeX) {
                assertEquals("pixel $x,$y stays in place", source[x, y], destination[x, y])
            }
        }
    }

    private fun spotted() =
        AvsFrame(SIZE, SIZE).also {
            // a block rather than a pixel, so a small warp cannot land on nothing
            for (y in SIZE / 2 - 3..SIZE / 2 + 3) {
                for (x in SIZE / 4 - 3..SIZE / 4 + 3) {
                    it[x, y] = WHITE
                }
            }
        }

    /** Every pixel its own color, so any resample shows. */
    private fun coded(
        width: Int,
        height: Int,
    ) = AvsFrame(width, height).also {
        for (y in 0 until height) {
            for (x in 0 until width) {
                it[x, y] = AvsFrame.OPAQUE or (x shl 16) or (y shl 8) or ((x + y) and 0xFF)
            }
        }
    }

    private companion object {
        const val SIZE = 65
        const val WIDE = 64
        const val TALL = 32
        const val GRID = 16
        const val ROTATION = 0.4
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
