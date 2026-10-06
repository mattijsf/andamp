// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import nl.mattix.andamp.core.playback.PcmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samples decoded elsewhere, through the chain a local file gets.
 *
 * Both chains are built from [audioChain]. These cases cover the plumbing:
 * nothing is dropped or altered on the way through, the visualizers are fed,
 * and a flush leaves no tail behind.
 */
class PcmChainTest {
    private fun tone(frames: Int): ByteArray {
        val bytes = ByteArray(frames * PcmProvider.BYTES_PER_FRAME)
        for (frame in 0 until frames) {
            val value = ((frame % 100) * 300 - 15_000).toShort()
            val at = frame * 4
            bytes[at] = (value.toInt() and 0xFF).toByte()
            bytes[at + 1] = (value.toInt() shr 8).toByte()
            bytes[at + 2] = bytes[at]
            bytes[at + 3] = bytes[at + 1]
        }
        return bytes
    }

    /** The output for [input], copied out of the buffer the next call overwrites. */
    private fun PcmChain.through(input: ByteArray): ByteArray {
        // process first: a call that needs more room replaces the output buffer
        val size = process(input, input.size)
        return output.copyOf(size)
    }

    @Test
    fun `what goes in comes out, sample for sample`() {
        val chain = PcmChain()
        val input = tone(256)

        val output = chain.through(input)

        assertEquals(input.size, output.size)
        assertTrue("a chain with nothing switched on leaves the samples unchanged", input.contentEquals(output))
    }

    @Test
    fun `zero bytes in is zero bytes out`() {
        val chain = PcmChain()

        assertEquals(0, chain.process(ByteArray(64), 0))
    }

    /** The visualizers read the tap. */
    @Test
    fun `the samples reach the visualizer tap`() {
        val chain = PcmChain()
        val input = tone(512)

        chain.process(input, input.size)

        assertTrue("the samples are written to the tap", chain.tap.writtenSamples > 0)
        assertEquals(PcmProvider.SAMPLE_RATE_HZ, chain.tap.sampleRateHz)
    }

    /** Checks that the curve reaches the chain; the equalizer's own tests cover what it does. */
    @Test
    fun `the tap is told how far its samples are ahead of the ear`() {
        val chain = PcmChain()
        chain.through(tone(4_096))

        chain.reportAhead(3_000)
        chain.holdAhead()

        assertEquals(3_000L, chain.tap.aheadSamples)
    }

    @Test
    fun `a curve the listener set changes what comes out`() {
        val input = tone(1_024)
        val flat = PcmChain().through(input)
        val shaped =
            PcmChain()
                .apply { setEqualizer(EqSettings(enabled = true, preampDb = 6f, bandsDb = List(10) { 12f })) }
                .through(input)

        assertTrue("the equalizer changes the output", !flat.contentEquals(shaped))
    }

    /**
     * A seek, a restart or another track, with a reverb switched on: the tail
     * of the old audio must not follow into the new.
     */
    @Test
    fun `a flush leaves no tail behind`() {
        val chain = PcmChain().apply { setDsp(REVERB_ON) }
        val input = tone(8_192)
        // past the rack's fade in and its controls' glide
        repeat(4) { chain.through(input) }
        val tail = chain.through(ByteArray(input.size))
        assertTrue("the reverb leaves a tail before the flush", tail.any { it != 0.toByte() })

        chain.through(input)
        chain.flush()
        val after = chain.through(ByteArray(input.size))

        assertTrue("no tail of the old audio comes through the flush", after.all { it == 0.toByte() })
    }

    /** The render thread calls this continuously, so the output buffer is reused. */
    @Test
    fun `the buffers are kept from one call to the next`() {
        val chain = PcmChain()
        val input = tone(4_096)
        chain.process(input, input.size)
        val kept = chain.output

        chain.process(input, input.size)

        assertSame("the output buffer is reused", kept, chain.output)
    }

    private companion object {
        val REVERB_ON =
            RackSettings(listOf(RackSlot(BuiltInEffects.REVERB, enabled = true, params = BuiltInEffects.reverb.defaults)))
    }
}
