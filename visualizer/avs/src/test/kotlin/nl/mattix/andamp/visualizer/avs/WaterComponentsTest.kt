// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Water, Water Bump and Scatter: what each does to a frame with a known answer. */
class WaterComponentsTest {
    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(WIDTH, HEIGHT)).also { it.reset(beat) }

    private fun grey(level: Int) = pixelOf(level, level, level)

    @Test
    fun `water spreads a lit pixel to its four neighbors at half strength and takes it away from itself`() {
        val frame = AvsFrame(WIDTH, HEIGHT).also { it[4, 4] = grey(200) }

        WaterRenderer(enabled = true).render(frame, AvsAudioFrame(), state())

        assertEquals("the pixel itself has four black neighbors", AvsFrame.OPAQUE, frame[4, 4])
        listOf(3 to 4, 5 to 4, 4 to 3, 4 to 5).forEach { (x, y) -> assertEquals(grey(100), frame[x, y]) }
        assertEquals("a diagonal neighbor is not touched", AvsFrame.OPAQUE, frame[5, 5])
    }

    @Test
    fun `water subtracts what a pixel was a frame ago`() {
        val water = WaterRenderer(enabled = true)
        val first = AvsFrame(WIDTH, HEIGHT).also { it[4, 4] = grey(200) }
        water.render(first, AvsAudioFrame(), state())

        // the same picture again: the neighbors would be 100, less the 0 they were; the lit pixel is 0 less its 200
        val second = AvsFrame(WIDTH, HEIGHT).also { it[4, 4] = grey(200) }
        water.render(second, AvsAudioFrame(), state())
        // and a third time, now less the 200 the first frame came in with
        val third = AvsFrame(WIDTH, HEIGHT).also { it[3, 4] = grey(200) }
        water.render(third, AvsAudioFrame(), state())

        assertEquals("100 from its lit neighbor, less the 200 it came in with", AvsFrame.OPAQUE, third[4, 4])
        assertEquals(grey(100), third[2, 4])
    }

    @Test
    fun `a corner has two neighbors and is not halved`() {
        val frame = AvsFrame(WIDTH, HEIGHT).also { it[1, 0] = grey(60) }

        WaterRenderer(enabled = true).render(frame, AvsAudioFrame(), state())

        assertEquals(grey(60), frame[0, 0])
        assertEquals("an edge pixel has three and is", grey(30), frame[2, 0])
    }

    @Test
    fun `water that is switched off leaves the frame alone`() {
        val frame = AvsFrame(WIDTH, HEIGHT).also { it[4, 4] = grey(200) }

        WaterRenderer.read(int32(0)).render(frame, AvsAudioFrame(), state())

        assertEquals(grey(200), frame[4, 4])
    }

    @Test
    fun `water with no settings at all is switched on`() {
        val frame = AvsFrame(WIDTH, HEIGHT).also { it[4, 4] = grey(200) }

        WaterRenderer.read(ByteArray(0)).render(frame, AvsAudioFrame(), state())

        assertEquals(grey(100), frame[3, 4])
    }

    @Test
    fun `scatter moves pixels no further than three away and leaves four rows at each end`() {
        val frame = AvsFrame(WIDTH, HEIGHT)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) frame[x, y] = pixelOf(x, y, 0)
        val before = frame.pixels.copyOf()

        ScatterRenderer(enabled = true, random = Random(7)).render(frame, AvsAudioFrame(), state())

        for (y in 0 until 4) for (x in 0 until WIDTH) assertEquals(before[y * WIDTH + x], frame[x, y])
        for (y in HEIGHT - 4 until HEIGHT) for (x in 0 until WIDTH) assertEquals(before[y * WIDTH + x], frame[x, y])
        var moved = 0
        for (y in 4 until HEIGHT - 4) {
            for (x in 4 until WIDTH - 4) {
                val from = frame[x, y]
                if (from != before[y * WIDTH + x]) moved++
                assertTrue("($x, $y) came from too far across", Math.abs(redOf(from) - x) <= 3)
                assertTrue("($x, $y) came from too far down", Math.abs(greenOf(from) - y) <= 3)
            }
        }
        assertTrue("most pixels moved: $moved", moved > (WIDTH - 8) * (HEIGHT - 8) / 2)
    }

    @Test
    fun `scatter's table holds whole offsets within three pixels each way`() {
        val offsets = ScatterRenderer.offsetsFor(width = 1000)

        offsets.forEach { offset ->
            val down = Math.round(offset / 1000.0).toInt()
            val across = offset - down * 1000
            assertTrue("$offset", down in -3..3 && across in -3..3)
        }
    }

    @Test
    fun `a frame too short to scatter is left alone`() {
        val frame = AvsFrame(WIDTH, 8).also { it[4, 4] = grey(200) }

        ScatterRenderer(enabled = true).render(frame, AvsAudioFrame(), AvsRenderState(AvsBuffers(WIDTH, 8)))

        assertEquals(grey(200), frame[4, 4])
    }

    @Test
    fun `still water shows the frame as it is`() {
        val frame = patterned()
        val before = frame.pixels.copyOf()

        WaterBumpRenderer(WaterBumpRenderer.Settings()).render(frame, AvsAudioFrame(), state())

        assertArrayEquals(before, frame.pixels)
    }

    @Test
    fun `a drop on a beat bends the picture around where it fell, and the ripple moves on`() {
        val bump = WaterBumpRenderer(WaterBumpRenderer.Settings(dropRadius = 6))
        val struck = patterned()
        bump.render(struck, AvsAudioFrame(), state(beat = true))

        assertNotEquals("the drop bends the frame it falls on", patterned().pixels.toList(), struck.pixels.toList())

        val after = patterned()
        bump.render(after, AvsAudioFrame(), state())
        assertNotEquals("the ripple is still there a frame later", patterned().pixels.toList(), after.pixels.toList())
        assertNotEquals("and has moved", struck.pixels.toList(), after.pixels.toList())
    }

    @Test
    fun `the outer ring of pixels is never bent`() {
        val bump = WaterBumpRenderer(WaterBumpRenderer.Settings(dropRadius = 30, depth = 5_000))
        val frame = patterned()

        bump.render(frame, AvsAudioFrame(), state(beat = true))

        val untouched = patterned()
        for (x in 0 until WIDTH) {
            assertEquals(untouched[x, 0], frame[x, 0])
            assertEquals(untouched[x, HEIGHT - 1], frame[x, HEIGHT - 1])
        }
    }

    @Test
    fun `a random drop too wide for the frame still falls inside it`() {
        val bump = WaterBumpRenderer(WaterBumpRenderer.Settings(randomDrop = true, dropRadius = 90), Random(3))

        repeat(4) { bump.render(patterned(), AvsAudioFrame(), state(beat = true)) }
    }

    @Test
    fun `water bump reads its settings in file order and keeps the defaults for what is missing`() {
        val full =
            WaterBumpRenderer.read(
                int32(1) + int32(3) + int32(900) + int32(1) + int32(0) + int32(2) + int32(25) + int32(0),
            )
        val short = WaterBumpRenderer.read(int32(1) + int32(3))

        // both are exercised through a beat: the shape of the result is not asserted, only that they run
        full.render(patterned(), AvsAudioFrame(), state(beat = true))
        short.render(patterned(), AvsAudioFrame(), state(beat = true))
    }

    private fun patterned() =
        AvsFrame(WIDTH, HEIGHT).also { frame ->
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) frame[x, y] = pixelOf(x * 8, y * 8, (x + y) * 4)
        }

    private fun int32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 24
    }
}
