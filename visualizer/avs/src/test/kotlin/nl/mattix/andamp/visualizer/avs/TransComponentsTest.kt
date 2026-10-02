// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invert, FadeOut, Blur, Buffer Save and Set Render Mode: what each reads from
 * its bytes, and what it does to the pixels or the render state. None of them
 * needs the evaluator.
 */
class TransComponentsTest {
    @Test
    fun `invert flips every channel and leaves alpha opaque`() {
        val frame = filled(0xFF204060.toInt())

        InvertRenderer(enabled = true).render(frame, audio(), state())

        assertEquals(0xFFDFBF9F.toInt(), frame[0, 0])
    }

    @Test
    fun `an invert that is switched off does nothing`() {
        val frame = filled(RED)

        InvertRenderer(enabled = false).render(frame, audio(), state())

        assertEquals(RED, frame[0, 0])
    }

    @Test
    fun `invert reads its one field`() {
        assertEquals(
            true,
            InvertRenderer.read(int32(1))!!.let {
                it.render(filled(RED), audio(), state())
                true
            },
        )
        assertNull("a body too short reads as null", InvertRenderer.read(byteArrayOf(1, 2)))
    }

    /** Integer steps, so a channel within one step of the target lands on it. */
    @Test
    fun `fade out walks each channel towards its color and stops there`() {
        val frame = filled(0xFF000000.toInt())
        val fade = FadeOutRenderer(speed = 10, target = 0xFF190000.toInt())

        fade.render(frame, audio(), state())
        assertEquals(0xFF0A0000.toInt(), frame[0, 0])

        fade.render(frame, audio(), state())
        fade.render(frame, audio(), state())
        assertEquals("the fade lands on the target without stepping past it", 0xFF190000.toInt(), frame[0, 0])
    }

    @Test
    fun `fade out goes down as well as up`() {
        val frame = filled(0xFFFFFFFF.toInt())

        FadeOutRenderer(speed = 5, target = AvsFrame.OPAQUE).render(frame, audio(), state())

        assertEquals(0xFFFAFAFA.toInt(), frame[0, 0])
    }

    @Test
    fun `a fade out at zero speed changes nothing`() {
        val frame = filled(RED)

        FadeOutRenderer(speed = 0, target = AvsFrame.OPAQUE).render(frame, audio(), state())

        assertEquals(RED, frame[0, 0])
    }

    @Test
    fun `fade out reads its speed and its color`() {
        val renderer = FadeOutRenderer.read(int32(7) + int32(0xFF0000))!!
        val frame = filled(AvsFrame.OPAQUE)

        renderer.render(frame, audio(), state())

        // 0xFF0000 is red as stored, and black takes seven steps towards it
        assertEquals(0xFF070000.toInt(), frame[0, 0])
    }

    @Test
    fun `blur spreads a lit pixel into its neighbors`() {
        val frame = AvsFrame(5, 5)
        frame[2, 2] = 0xFFFFFFFF.toInt()

        BlurRenderer(AvsBlurLevel.LIGHT).render(frame, audio(), state())

        assertNotEquals("the neighbor picks up some light", AvsFrame.OPAQUE, frame[1, 2])
        assertTrue("the center dims", (frame[2, 2] and 0xFF) < 0xFF)
    }

    @Test
    fun `a heavier blur takes more from the pixel and gives more to the neighbors`() {
        val light = AvsFrame(9, 9).also { it[4, 4] = 0xFFFFFFFF.toInt() }
        val heavy = AvsFrame(9, 9).also { it[4, 4] = 0xFFFFFFFF.toInt() }

        BlurRenderer(AvsBlurLevel.LIGHT).render(light, audio(), state())
        BlurRenderer(AvsBlurLevel.HEAVY).render(heavy, audio(), state())

        // e_blur's kernels all reach one neighbor; the levels differ in
        // weight: light keeps 3/4 of the center, heavy drops it
        assertEquals(AvsFrame.OPAQUE, light[2, 4])
        assertEquals(AvsFrame.OPAQUE, heavy[2, 4])
        assertTrue("heavy keeps less of the center than light", (heavy[4, 4] and 0xFF) < (light[4, 4] and 0xFF))
        assertTrue("heavy gives the neighbor more than light", (heavy[3, 4] and 0xFF) > (light[3, 4] and 0xFF))
    }

    @Test
    fun `a blur of none leaves the frame alone`() {
        val frame = AvsFrame(3, 3).also { it[1, 1] = RED }

        BlurRenderer(AvsBlurLevel.NONE).render(frame, audio(), state())

        assertEquals(RED, frame[1, 1])
        assertEquals(AvsFrame.OPAQUE, frame[0, 1])
    }

    /** The file's order is not the enum's: 0 none, 1 medium, 2 light, 3 heavy. */
    @Test
    fun `blur reads its level out of the order the file stores them in`() {
        val medium = AvsFrame(9, 9).also { it[4, 4] = 0xFFFFFFFF.toInt() }
        val light = AvsFrame(9, 9).also { it[4, 4] = 0xFFFFFFFF.toInt() }

        BlurRenderer.read(int32(1))!!.render(medium, audio(), state())
        BlurRenderer.read(int32(2))!!.render(light, audio(), state())

        // both reach one pixel; light gives a neighbor a sixteenth, medium an eighth
        assertNotEquals(AvsFrame.OPAQUE, medium[3, 4])
        assertTrue(
            "level 1 blurs more than level 2",
            (medium[3, 4] and 0xFF) > (light[3, 4] and 0xFF),
        )
    }

    @Test
    fun `buffer save puts the frame away and gets it back`() {
        val state = state()
        val frame = filled(RED)

        BufferSaveRenderer(AvsBufferAction.SAVE, buffer = 0, blend = AvsBlendMode.REPLACE, adjust = 255)
            .render(frame, audio(), state)
        frame.clear()
        BufferSaveRenderer(AvsBufferAction.RESTORE, buffer = 0, blend = AvsBlendMode.REPLACE, adjust = 255)
            .render(frame, audio(), state)

        assertEquals(RED, frame[0, 0])
    }

    @Test
    fun `an alternating buffer save flips every frame`() {
        val state = state()
        val frame = filled(RED)
        val alternating =
            BufferSaveRenderer(
                AvsBufferAction.ALTERNATE_SAVE_RESTORE,
                buffer = 0,
                blend = AvsBlendMode.REPLACE,
                adjust = 255,
            )

        alternating.render(frame, audio(), state) // saves red
        frame.clear()
        alternating.render(frame, audio(), state) // restores it

        assertEquals(RED, frame[0, 0])
    }

    @Test
    fun `a restore uses its blend mode`() {
        val state = state()
        val frame = filled(RED)
        BufferSaveRenderer(AvsBufferAction.SAVE, 0, AvsBlendMode.REPLACE, 255).render(frame, audio(), state)
        frame.pixels.fill(GREEN)

        BufferSaveRenderer(AvsBufferAction.RESTORE, 0, AvsBlendMode.MAXIMUM, 255).render(frame, audio(), state)

        assertEquals(0xFFFFFF00.toInt(), frame[0, 0])
    }

    @Test
    fun `buffer save numbers its buffers from one`() {
        // e_buffersave passes config.buffer - 1 to get_buffer; so do the
        // Effect List's buffer fields
        val renderer = BufferSaveRenderer.read(int32(0) + int32(1) + int32(0) + int32(255))!!
        val state = state()
        renderer.render(filled(RED), audio(), state)

        assertEquals("buffer 1 in the file is bank 0", RED, state.buffers[0]!![0, 0])
    }

    /** The blend applies to a save too, and a restore of an unwritten buffer does nothing. */
    @Test
    fun `a save blends into the buffer and an empty restore does nothing`() {
        val state = state()
        state.buffers[0]!!.pixels.fill(0xFF004000.toInt())
        // additive save: frame + what the buffer held
        BufferSaveRenderer(AvsBufferAction.SAVE, 0, AvsBlendMode.ADDITIVE, 255)
            .render(filled(0xFF400000.toInt()), audio(), state)
        assertEquals(0xFF404000.toInt(), state.buffers.peek(0)!![0, 0])

        val frame = filled(RED)
        BufferSaveRenderer(AvsBufferAction.RESTORE, 5, AvsBlendMode.REPLACE, 255).render(frame, audio(), state)
        assertEquals("restoring an unwritten buffer changes nothing", RED, frame[0, 0])
    }

    @Test
    fun `set render mode changes how what comes after it draws`() {
        val state = state()

        SetRenderModeRenderer(enabled = true, blend = AvsBlendMode.ADDITIVE, adjust = 128, lineSize = 3)
            .render(filled(RED), audio(), state)

        assertEquals(AvsBlendMode.ADDITIVE, state.renderBlend)
        assertEquals(128, state.renderAdjust)
        assertEquals(3, state.lineSize)
    }

    @Test
    fun `a set render mode that is switched off leaves the mode alone`() {
        val state = state()

        SetRenderModeRenderer(enabled = false, blend = AvsBlendMode.XOR, adjust = 1, lineSize = 9)
            .render(filled(RED), audio(), state)

        assertEquals(AvsBlendMode.REPLACE, state.renderBlend)
    }

    @Test
    fun `set render mode reads its enabled bit out of the fourth byte`() {
        // blend, adjust, line size, then the flag byte whose top bit is
        // r_linemode's 0x80000000
        val on = SetRenderModeRenderer.read(byteArrayOf(1, 100, 2, 0x80.toByte()))!!
        val off = SetRenderModeRenderer.read(byteArrayOf(1, 100, 2, 0))!!
        val state = state()

        on.render(filled(RED), audio(), state)
        assertEquals(AvsBlendMode.ADDITIVE, state.renderBlend)

        state.reset()
        off.render(filled(RED), audio(), state)
        assertEquals(AvsBlendMode.REPLACE, state.renderBlend)
    }

    @Test
    fun `reset returns the render mode to its defaults`() {
        val state = state()
        state.setRenderMode(AvsBlendMode.XOR, 10, 4)

        state.reset()

        assertEquals(AvsBlendMode.REPLACE, state.renderBlend)
        assertEquals(1, state.lineSize)
    }

    private fun filled(colour: Int) = AvsFrame(2, 2, IntArray(4) { colour })

    private fun audio() = AvsAudioFrame()

    private fun state() = AvsRenderState(AvsBuffers(2, 2))

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val GREEN = 0xFF00FF00.toInt()
    }
}
