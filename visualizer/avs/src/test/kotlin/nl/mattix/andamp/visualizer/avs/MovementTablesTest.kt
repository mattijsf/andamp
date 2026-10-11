// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Movement's two effects that are tables: where each pixel is read from, and that the frame follows. */
class MovementTablesTest {
    @Test
    fun `slight fuzzify reads every pixel from itself or a neighbor`() {
        val table = MovementTables.slightFuzzify(Random(1)).build(WIDTH, HEIGHT)

        var moved = 0
        table.forEachIndexed { at, from ->
            if (from != at) moved++
            // one step across and one row up or down, as an index: a step off the end of a row lands on the next
            assertTrue("pixel $at reads $from", Math.abs(from - at) <= WIDTH + 1)
            assertTrue(from in 0 until WIDTH * HEIGHT)
        }
        assertTrue("most pixels move: $moved of ${table.size}", moved > table.size / 2)
    }

    @Test
    fun `blocky partial out moves one pixel in four toward the edge and leaves the rest`() {
        val table = MovementTables.BLOCKY_PARTIAL_OUT.build(WIDTH, HEIGHT)

        fun from(
            x: Int,
            y: Int,
        ) = table[x + y * WIDTH]

        assertEquals("a pixel with a second bit set stays", 2 + 5 * WIDTH, from(2, 5))
        assertEquals(6 + 4 * WIDTH, from(6, 4))
        // (0, 0): 16 + (0 - 16) * 7 / 8 = 2, 8 + (0 - 8) * 7 / 8 = 1
        assertEquals(2 + 1 * WIDTH, from(0, 0))
        // (1, 1) is rounded down to (0, 0) first
        assertEquals(2 + 1 * WIDTH, from(1, 1))
        assertEquals("the center reads itself", WIDTH / 2 + HEIGHT / 2 * WIDTH, from(WIDTH / 2, HEIGHT / 2))
    }

    @Test
    fun `the frame is read through the table`() {
        val shift = MovementTable { width, height -> IntArray(width * height) { (it + 1) % (width * height) } }
        val frame = AvsFrame(WIDTH, HEIGHT).also { it[5, 0] = WHITE }

        MovementTableRenderer(shift, AvsBlendMode.REPLACE).render(frame, AvsAudioFrame(), state())

        assertEquals(WHITE, frame[4, 0])
        assertEquals(AvsFrame.OPAQUE, frame[5, 0])
    }

    @Test
    fun `a fifty-fifty movement mixes the moved frame with the one that came in`() {
        val shift = MovementTable { width, height -> IntArray(width * height) { (it + 1) % (width * height) } }
        val frame = AvsFrame(WIDTH, HEIGHT).also { it[5, 0] = WHITE }

        MovementTableRenderer(shift, AvsBlendMode.FIFTY_FIFTY).render(frame, AvsAudioFrame(), state())

        assertEquals(HALF, redOf(frame[4, 0]))
        assertEquals(HALF, redOf(frame[5, 0]))
    }

    @Test
    fun `the table follows the frame's size`() {
        val renderer = MovementTableRenderer(MovementTables.BLOCKY_PARTIAL_OUT, AvsBlendMode.REPLACE)

        renderer.render(AvsFrame(WIDTH, HEIGHT), AvsAudioFrame(), state())
        // a table built for the first size would index past this one
        renderer.render(AvsFrame(WIDTH / 2, HEIGHT / 2), AvsAudioFrame(), AvsRenderState(AvsBuffers(WIDTH / 2, HEIGHT / 2)))
    }

    private fun state() = AvsRenderState(AvsBuffers(WIDTH, HEIGHT))

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 16
        const val WHITE = 0xFFFFFFFF.toInt()
        const val HALF = 127
    }
}
