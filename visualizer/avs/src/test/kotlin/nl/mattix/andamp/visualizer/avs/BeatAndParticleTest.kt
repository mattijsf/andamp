// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Custom BPM and Moving Particle, neither of which needs the evaluator.
 *
 * Custom BPM rewrites the beat in the render state, so most of its tests assert
 * what the rest of the preset then sees.
 */
class BeatAndParticleTest {
    @Test
    fun `reverse inverts the beat for everything after it`() {
        val reverse = bpm(AvsBpmMode.REVERSE)

        assertEquals(false, beatAfter(reverse, heard = true))
        assertEquals(true, beatAfter(reverse, heard = false))
    }

    @Test
    fun `skip passes every nth beat and swallows the rest`() {
        val skipOne = bpm(AvsBpmMode.SKIP, skip = 1)

        val passed = (1..6).map { beatAfter(skipOne, heard = true) }

        assertEquals(listOf(false, true, false, true, false, true), passed)
    }

    @Test
    fun `skip counts beats, not frames`() {
        val skipOne = bpm(AvsBpmMode.SKIP, skip = 1)

        assertEquals(false, beatAfter(skipOne, heard = true))
        repeat(10) { assertEquals(false, beatAfter(skipOne, heard = false)) }
        assertEquals("quiet frames do not count toward the skip", true, beatAfter(skipOne, heard = true))
    }

    @Test
    fun `the first few beats can be thrown away`() {
        val skipping = bpm(AvsBpmMode.SKIP, skip = 0, skipFirst = 2)

        val passed = (1..4).map { beatAfter(skipping, heard = true) }

        assertEquals(listOf(false, false, true, true), passed)
    }

    /** Arbitrary makes beats on the wall clock, whatever the music or the frame rate. */
    @Test
    fun `arbitrary beats on the wall clock and ignores the audio`() {
        var now = 0L
        val everySecond = CustomBpmRenderer(true, AvsBpmMode.ARBITRARY, beatIntervalMs = 1000, skip = 0, skipFirst = 0) { now }

        // sixty frames in 1980 ms: one beat, on the first frame past one second
        val beats =
            (1..60).count {
                now += 33
                beatAfter(everySecond, heard = false)
            }

        assertEquals(1, beats)
        now += 1000
        assertTrue(beatAfter(everySecond, heard = false))
    }

    @Test
    fun `an arbitrary interval of zero never beats`() {
        val broken = bpm(AvsBpmMode.ARBITRARY, intervalMs = 0)

        assertEquals(0, (1..30).count { beatAfter(broken, heard = true) })
    }

    @Test
    fun `a custom bpm that is switched off leaves the beat alone`() {
        val off = CustomBpmRenderer(enabled = false, AvsBpmMode.REVERSE, 0, 0, 0)

        assertEquals(true, beatAfter(off, heard = true))
    }

    @Test
    fun `custom bpm reads its seven fields, the mode being three radio flags`() {
        // enabled, arbitrary flag, skip flag, invert flag, then the three values
        val inverted =
            CustomBpmRenderer.read(int32(1) + int32(0) + int32(0) + int32(1) + int32(120) + int32(0) + int32(0))!!
        val skipping =
            CustomBpmRenderer.read(int32(1) + int32(0) + int32(1) + int32(0) + int32(120) + int32(1) + int32(0))!!

        assertEquals("the invert flag reads as reverse", false, beatAfter(inverted, heard = true))
        assertEquals("the skip flag reads as skip", false, beatAfter(skipping, heard = true))
        assertNull(CustomBpmRenderer.read(int32(1)))
    }

    @Test
    fun `a moving particle draws a disc that moves`() {
        val first = AvsFrame(SIZE, SIZE)
        val second = AvsFrame(SIZE, SIZE)
        val particle = particle()

        particle.render(first, AvsAudioFrame(), state())
        // the spring drifts a fraction of a pixel a frame; give it time to show
        repeat(14) { particle.render(AvsFrame(SIZE, SIZE), AvsAudioFrame(), state()) }
        particle.render(second, AvsAudioFrame(), state())

        assertTrue("the particle draws something", first.pixels.any { it != AvsFrame.OPAQUE })
        assertNotEquals("the particle moves", first.pixels.toList(), second.pixels.toList())
    }

    /** e_movingparticle: a beat aims the spring at a new random attractor. */
    @Test
    fun `a particle that gets beats ends up elsewhere than one that gets none`() {
        val kicked = MovingParticleRenderer(true, false, WHITE, 32, 3, 3, AvsBlendMode.REPLACE)
        val quiet = MovingParticleRenderer(true, false, WHITE, 32, 3, 3, AvsBlendMode.REPLACE)

        repeat(40) {
            kicked.render(AvsFrame(SIZE, SIZE), AvsAudioFrame(), state(beat = it % 10 == 0))
            quiet.render(AvsFrame(SIZE, SIZE), AvsAudioFrame(), state(beat = false))
        }
        val kickedFrame = AvsFrame(SIZE, SIZE)
        val quietFrame = AvsFrame(SIZE, SIZE)
        kicked.render(kickedFrame, AvsAudioFrame(), state())
        quiet.render(quietFrame, AvsAudioFrame(), state())

        assertNotEquals(kickedFrame.pixels.toList(), quietFrame.pixels.toList())
    }

    /** e_movingparticle: `cur_size = (cur_size + size) / 2`, so the beat size halves its way back. */
    @Test
    fun `after a beat the particle shrinks back a half at a time`() {
        val particle = MovingParticleRenderer(true, true, WHITE, 16, 2, 32, AvsBlendMode.REPLACE)
        val beat = AvsFrame(SIZE, SIZE)
        val next = AvsFrame(SIZE, SIZE)
        val after = AvsFrame(SIZE, SIZE)

        particle.render(beat, AvsAudioFrame(), state(beat = true))
        particle.render(next, AvsAudioFrame(), state())
        particle.render(after, AvsAudioFrame(), state())

        val beatLit = beat.pixels.count { it != AvsFrame.OPAQUE }
        val nextLit = next.pixels.count { it != AvsFrame.OPAQUE }
        val afterLit = after.pixels.count { it != AvsFrame.OPAQUE }
        assertTrue("the frame after the beat is smaller but not empty: $beatLit then $nextLit", nextLit in 1 until beatLit)
        assertTrue("the frame after that is smaller again: $nextLit then $afterLit", afterLit in 1 until nextLit)
    }

    /** Size is a diameter in pixels, and a size of one takes the single-pixel path. */
    @Test
    fun `a size-one particle is a single pixel`() {
        val particle = MovingParticleRenderer(true, false, WHITE, 16, 1, 1, AvsBlendMode.REPLACE)
        val frame = AvsFrame(SIZE, SIZE)

        particle.render(frame, AvsAudioFrame(), state())

        assertEquals(1, frame.pixels.count { it != AvsFrame.OPAQUE })
    }

    @Test
    fun `a particle that is switched off draws nothing`() {
        val frame = AvsFrame(SIZE, SIZE)

        MovingParticleRenderer(false, false, WHITE, 16, 8, 16, AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertTrue(frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    @Test
    fun `it draws bigger on a beat only when grow on beat is set`() {
        val quiet = AvsFrame(SIZE, SIZE)
        val onBeat = AvsFrame(SIZE, SIZE)
        val ignoring = AvsFrame(SIZE, SIZE)

        MovingParticleRenderer(true, true, WHITE, 16, 4, 40, AvsBlendMode.REPLACE)
            .render(quiet, AvsAudioFrame(), state(beat = false))
        MovingParticleRenderer(true, true, WHITE, 16, 4, 40, AvsBlendMode.REPLACE)
            .render(onBeat, AvsAudioFrame(), state(beat = true))
        MovingParticleRenderer(true, false, WHITE, 16, 4, 40, AvsBlendMode.REPLACE)
            .render(ignoring, AvsAudioFrame(), state(beat = true))

        val quietLit = quiet.pixels.count { it != AvsFrame.OPAQUE }
        val beatLit = onBeat.pixels.count { it != AvsFrame.OPAQUE }
        assertTrue("a beat grows the particle: $quietLit then $beatLit", beatLit > quietLit)
        assertEquals("without grow on beat the size stays", quietLit, ignoring.pixels.count { it != AvsFrame.OPAQUE })
    }

    @Test
    fun `moving particle reads its flags out of one bitfield`() {
        val renderer =
            MovingParticleRenderer.read(int32(0x03) + int32(0xFF0000) + int32(16) + int32(8) + int32(16) + int32(0))!!
        val frame = AvsFrame(SIZE, SIZE)

        renderer.render(frame, AvsAudioFrame(), state())

        // colors are stored (A)RGB: 0xFF0000 is red as it is
        assertTrue("the particle is red", frame.pixels.any { it == 0xFFFF0000.toInt() })
    }

    @Test
    fun `a particle body that runs out is null`() {
        assertNull(MovingParticleRenderer.read(int32(1) + int32(2)))
    }

    private var clockMs = 0L

    private fun bpm(
        mode: AvsBpmMode,
        intervalMs: Long = 500,
        skip: Int = 0,
        skipFirst: Int = 0,
    ) = CustomBpmRenderer(true, mode, intervalMs, skip, skipFirst) { clockMs }

    /** What the rest of the preset would see, one frame at a time. */
    private fun beatAfter(
        renderer: AvsComponentRenderer,
        heard: Boolean,
    ): Boolean {
        val state = state(beat = heard)
        renderer.render(AvsFrame(1, 1), AvsAudioFrame(beat = heard), state)
        return state.beat
    }

    private fun particle() = MovingParticleRenderer(true, false, WHITE, 24, 6, 6, AvsBlendMode.REPLACE)

    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(SIZE, SIZE)).also { it.beat = beat }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        const val SIZE = 65
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
