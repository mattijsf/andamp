// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 48 kHz arriving, 44.1 kHz leaving: the count of frames out for a count of frames in. */
class PcmRateTest {
    @Test
    fun `a second of 48 kHz becomes a second of 44 point 1`() {
        val rate = PcmRate(from = 48_000)
        val block = ShortArray(BLOCK_FRAMES * 2)
        val into = ShortArray(rate.room(block.size))
        var frames = 0

        // a hundred blocks is one second of a 48 kHz file, fed block by block, so the
        // cursor and the filter's history are carried across block boundaries
        repeat(BLOCKS_PER_SECOND) { frames += rate.convert(block, block.size, into) / 2 }

        assertEquals(44_100, frames)
    }

    @Test
    fun `every block of a steady stream converts to the same size`() {
        val rate = PcmRate(from = 48_000)
        val block = ShortArray(BLOCK_FRAMES * 2)
        val into = ShortArray(rate.room(block.size))

        // 480 in, 441 out, and the cursor is back where it started after every block
        repeat(BLOCKS_PER_SECOND) { assertEquals(441 * 2, rate.convert(block, block.size, into)) }
    }

    @Test
    fun `a rising ramp stays rising`() {
        val rate = PcmRate(from = 48_000)
        val frames = 1_000
        val ramp = ShortArray(frames * 2) { index -> (index / 2).toShort() }
        val into = ShortArray(rate.room(ramp.size))

        val written = rate.convert(ramp, ramp.size, into)

        assertTrue(written > 0)
        // a lowpass of a slope is the same slope; a fall means the cursor went backwards
        // or a tap read the wrong frame
        for (index in 2 until written step 2) {
            assertTrue("the output rises at $index", into[index] >= into[index - 2])
        }
    }

    @Test
    fun `a second of 22 point 05 kHz becomes a second of 44 point 1`() {
        val rate = PcmRate(from = 22_050)
        val block = ShortArray(LOW_BLOCK_FRAMES * 2)
        val into = ShortArray(rate.room(block.size))
        var frames = 0

        repeat(LOW_BLOCKS) { frames += rate.convert(block, block.size, into) / 2 }

        // a second of 22.05 kHz is a second of 44.1 kHz, to within a frame
        assertEquals(44_100.0, frames.toDouble(), 1.0)
    }

    @Test
    fun `the output buffer is always big enough for what is written`() {
        val rate = PcmRate(from = 48_000)
        val odd = ShortArray(ODD_FRAMES * 2)
        val into = ShortArray(rate.room(odd.size))

        // an odd block size puts the cursor somewhere different on every block
        repeat(BLOCKS_PER_SECOND) { assertTrue(rate.convert(odd, odd.size, into) <= into.size) }
    }

    @Test
    fun `a seek forgets the frame before it`() {
        val loud = ShortArray(CARRY_FRAMES * 2) { Short.MAX_VALUE }
        val quiet = ShortArray(CARRY_FRAMES * 2)
        val carried = ShortArray(PcmRate(from = 48_000).room(loud.size))
        val dropped = ShortArray(carried.size)

        jump(PcmRate(from = 48_000), loud, quiet, carried, reset = false)
        jump(PcmRate(from = 48_000), loud, quiet, dropped, reset = true)

        // the first frames after a jump are filtered from the frames before it; without
        // the reset the silence starts at the old level
        assertTrue(carried[0] > Short.MAX_VALUE / 4)
        assertEquals(0, dropped[0].toInt())
    }

    /** A loud stretch, then a silent one, with or without the reset a seek would do between them. */
    private fun jump(
        rate: PcmRate,
        loud: ShortArray,
        quiet: ShortArray,
        into: ShortArray,
        reset: Boolean,
    ) {
        rate.convert(loud, loud.size, into)
        if (reset) rate.reset()
        rate.convert(quiet, quiet.size, into)
    }

    private companion object {
        /** A hundredth of a second at 48 kHz. */
        const val BLOCK_FRAMES = 480
        const val BLOCKS_PER_SECOND = 100
        const val ODD_FRAMES = 517

        /** Fifty of these is a second of a 22.05 kHz file. */
        const val LOW_BLOCK_FRAMES = 441
        const val LOW_BLOCKS = 50

        /** A block size whose cursor lands part way into the frame before the next block's first. */
        const val CARRY_FRAMES = 500
    }
}
