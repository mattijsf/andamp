// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipeline, and the structure an Effect List imposes on what is under it:
 * whether a list runs, what its canvas starts as, which buffer it reads or
 * writes, and how the result is folded back.
 */
class AvsEngineTest {
    @Test
    fun `a preset with nothing in it renders a black frame`() {
        val engine = AvsEngine(4, 4)
        engine.load(AvsPreset(clearEveryFrame = true, components = emptyList()))

        assertTrue(engine.render().pixels.all { it == AvsFrame.OPAQUE })
    }

    @Test
    fun `a frame starts from the last one unless the preset clears every frame`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(RED)

        engine.load(AvsPreset(clearEveryFrame = false, components = emptyList()))
        engine.render()
        assertTrue("a frame that does not clear keeps what was there", engine.frame.pixels.all { it == RED })

        engine.load(AvsPreset(clearEveryFrame = true, components = emptyList()))
        engine.render()
        assertTrue(engine.frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** Blur and Invert have readers, but an empty body gives no renderer; Texer II has no reader. */
    @Test
    fun `components without a renderer are named`() {
        val engine = AvsEngine(2, 2)

        engine.load(preset(builtin("Blur"), builtin("Invert"), ape("Texer II")))

        assertEquals(listOf("Blur", "Invert", "Texer II"), engine.unimplemented)
    }

    @Test
    fun `two identical stateful components keep separate state`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(GREEN)
        val body = int32(0xFF0000) + int32(0) + int32(2) // red, replace, every two beats
        engine.load(
            preset(
                AvsComponent.Builtin(ON_BEAT_CLEAR, AvsComponentType("OnBeat Clear", "Render"), body),
                AvsComponent.Builtin(ON_BEAT_CLEAR, AvsComponentType("OnBeat Clear", "Render"), body),
            ),
        )

        engine.render(AvsAudioFrame(beat = true))

        // one shared renderer would count this beat twice and fire; each of two counts it once
        assertTrue("each instance counts the beat once", engine.frame.pixels.all { it == GREEN })

        engine.render(AvsAudioFrame(beat = true))
        assertTrue("both instances fire on the second beat", engine.frame.pixels.all { it == RED })
    }

    /** An APE names itself in the file and is looked up by that name in the same table as the built-ins. */
    @Test
    fun `an ape with an implemented name runs through the same table`() {
        val engine = AvsEngine(2, 2)
        val grey = 0xFF7F7F7F.toInt()
        engine.frame.pixels.fill(grey)
        engine.load(preset(AvsComponent.Ape("Color Reduction", ByteArray(APE_PATH_PADDING) + int32(1))))

        engine.render()

        assertEquals(emptyList<String>(), engine.unimplemented)
        assertTrue("two levels change every mid-gray pixel", engine.frame.pixels.all { it != grey })
    }

    @Test
    fun `an unknown ape is reported missing`() {
        val engine = AvsEngine(2, 2)

        engine.load(preset(AvsComponent.Ape("Nonsense FX", ByteArray(4))))

        assertEquals(listOf("Nonsense FX"), engine.unimplemented)
    }

    @Test
    fun `an effect list that only runs on beat waits for one`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(INK)
        val invert = AvsComponent.Builtin(INVERT, AvsComponentType("Invert", "Trans"), int32(1))
        // enabled is off: on-beat is an OR with enabled, not a gate on it, so
        // an enabled list with on-beat set always renders
        engine.load(
            preset(
                list(
                    AvsEffectListConfig(
                        enabled = false,
                        input = AvsBlendMode.REPLACE,
                        onlyOnBeat = true,
                        onBeatFrames = 1,
                    ),
                    invert,
                ),
            ),
        )

        engine.render(AvsAudioFrame(beat = false))
        assertTrue("the list does not run without a beat", engine.frame.pixels.all { it == INK })

        engine.render(AvsAudioFrame(beat = true))
        assertTrue("the list runs on a beat", engine.frame.pixels.all { it == INK_INVERTED })
    }

    @Test
    fun `flatten reaches arbitrarily deep`() {
        val innermost = builtin("Blur")
        val nested = preset(list(config(), list(config(), list(config(), innermost))))

        val all = nested.flatten()

        assertEquals(4, all.size)
        assertTrue("the innermost component is counted", innermost in all)
    }

    @Test
    fun `supported names apes and built-ins`() {
        assertTrue(AvsEngine.supported.containsAll(setOf("Color Reduction", "Channel Shift", "Simple")))
    }

    @Test
    fun `a component is named once however many times it appears`() {
        val engine = AvsEngine(2, 2)

        engine.load(preset(builtin("Blur"), builtin("Blur"), builtin("Invert")))

        assertEquals(listOf("Blur", "Invert"), engine.unimplemented)
    }

    @Test
    fun `loading another preset replaces the list of what is missing`() {
        val engine = AvsEngine(2, 2)
        engine.load(preset(builtin("Blur")))

        engine.load(preset())

        assertEquals(emptyList<String>(), engine.unimplemented)
    }

    @Test
    fun `components inside a list are found too`() {
        val engine = AvsEngine(2, 2)

        engine.load(preset(list(config(), builtin("Blur"))))

        assertEquals(listOf("Blur"), engine.unimplemented)
    }

    @Test
    fun `a disabled effect list does not run`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(RED)

        engine.load(preset(list(config(enabled = false, clearFrame = true))))
        engine.render()

        assertTrue("a switched-off list does not run", engine.frame.pixels.all { it == RED })
    }

    @Test
    fun `a list that clears its canvas puts black back through a replacing output`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(RED)

        engine.load(preset(list(config(clearFrame = true, output = AvsBlendMode.REPLACE))))
        engine.render()

        assertTrue(engine.frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    @Test
    fun `a list whose output is ignored leaves the frame alone`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(RED)

        engine.load(preset(list(config(clearFrame = true, output = AvsBlendMode.IGNORE))))
        engine.render()

        assertTrue(engine.frame.pixels.all { it == RED })
    }

    /**
     * Guards against a list's canvas starting as a copy of the frame below:
     * with input ignore and output additive the frame would add itself to
     * itself every frame.
     */
    @Test
    fun `an additive list does not add the frame to itself`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(RED)

        engine.load(preset(list(config(input = AvsBlendMode.IGNORE, output = AvsBlendMode.ADDITIVE))))
        repeat(3) { engine.render() }

        assertTrue("the frame is not added to itself", engine.frame.pixels.all { it == RED })
    }

    @Test
    fun `the input blend decides what the list starts from`() {
        val invert = AvsComponent.Builtin(INVERT, AvsComponentType("Invert", "Trans"), int32(1))
        val replaced = AvsEngine(2, 2)
        replaced.frame.pixels.fill(RED)
        replaced.load(
            preset(list(config(input = AvsBlendMode.REPLACE, output = AvsBlendMode.REPLACE), invert)),
        )

        val ignored = AvsEngine(2, 2)
        ignored.frame.pixels.fill(RED)
        ignored.load(
            preset(list(config(input = AvsBlendMode.IGNORE, output = AvsBlendMode.REPLACE), invert)),
        )

        replaced.render()
        ignored.render()

        // inverted red under REPLACE; the IGNORE canvas started black and inverts to white
        assertTrue(replaced.frame.pixels.all { it == 0xFF00FFFF.toInt() })
        assertTrue(ignored.frame.pixels.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `the output blend is the one the list asked for`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(GREEN)

        // a cleared canvas folded back with MAXIMUM cannot darken anything
        engine.load(preset(list(config(clearFrame = true, output = AvsBlendMode.MAXIMUM))))
        engine.render()

        assertTrue(engine.frame.pixels.all { it == GREEN })
    }

    @Test
    fun `a buffer save round-trips through the bank, and a list input masks through it`() {
        val engine = AvsEngine(2, 2)
        engine.frame.pixels.fill(RED)

        // save, clear, restore; file numbers are one-based
        engine.load(
            preset(
                bufferSave(action = 0, fileNumber = 1),
                list(config(input = AvsBlendMode.REPLACE, output = AvsBlendMode.REPLACE, clearFrame = true)),
                bufferSave(action = 1, fileNumber = 1),
            ),
        )
        engine.render()
        assertTrue("the saved buffer is restored", engine.frame.pixels.all { it == RED })

        // as a list input the buffer is a per-pixel mask, not a restore: a
        // full-bright mask lets the frame below fully into the canvas
        val masked = AvsEngine(2, 2)
        masked.frame.pixels.fill(RED)
        masked.load(
            preset(
                bufferSave(action = 0, fileNumber = 2),
                list(config(input = AvsBlendMode.BUFFER, inBuffer = 2, output = AvsBlendMode.REPLACE)),
            ),
        )
        masked.render()
        assertTrue("a bright mask passes the frame through", masked.frame.pixels.all { it == RED })
    }

    private fun bufferSave(
        action: Int,
        fileNumber: Int,
    ): AvsComponent.Builtin {
        val body = int32(action) + int32(fileNumber) + int32(0) + int32(255)
        return AvsComponent.Builtin(BUFFER_SAVE, AvsComponentType("Buffer Save", "Misc"), body)
    }

    @Test
    fun `a buffer nobody used is never made`() {
        val buffers = AvsBuffers(2, 2)

        assertEquals(0, buffers.allocated())
        buffers[3]
        assertEquals(1, buffers.allocated())
        buffers[3]
        assertEquals("asking twice allocates one buffer", 1, buffers.allocated())
    }

    @Test
    fun `the same buffer comes back each time it is asked for`() {
        val buffers = AvsBuffers(2, 2)

        assertSame(buffers[0], buffers[0])
        assertNotSame(buffers[0], buffers[1])
    }

    @Test
    fun `an index AVS has no buffer for is null`() {
        val buffers = AvsBuffers(2, 2)

        assertNull(buffers[buffers.size])
        assertNull(buffers[-1])
    }

    @Test
    fun `resizing gives fresh buffers of the new size`() {
        val buffers = AvsBuffers(2, 2)
        buffers[0]

        val resized = buffers.resizedTo(4, 4)

        assertEquals(0, resized.allocated())
        assertEquals(4, resized[0]!!.width)
        assertSame("a resize to the same size returns the same buffers", buffers, buffers.resizedTo(2, 2))
    }

    @Test
    fun `resizing the engine gives a frame of the new size`() {
        val engine = AvsEngine(2, 2)

        engine.resize(8, 6)

        assertEquals(8, engine.frame.width)
        assertEquals(6, engine.frame.height)
    }

    @Test
    fun `nested lists are walked all the way down`() {
        val engine = AvsEngine(2, 2)

        engine.load(
            preset(
                AvsComponent.EffectList(
                    config(),
                    listOf(builtin("Blur"), AvsComponent.EffectList(config(), listOf(builtin("Water")), ByteArray(0))),
                    ByteArray(0),
                ),
            ),
        )

        assertEquals(listOf("Blur", "Water"), engine.unimplemented)
    }

    private fun preset(vararg components: AvsComponent) = AvsPreset(clearEveryFrame = false, components = components.toList())

    private fun list(
        config: AvsEffectListConfig,
        vararg children: AvsComponent,
    ) = AvsComponent.EffectList(config, children.toList(), ByteArray(0))

    private fun config(
        enabled: Boolean = true,
        clearFrame: Boolean = false,
        input: AvsBlendMode = AvsBlendMode.IGNORE,
        output: AvsBlendMode = AvsBlendMode.REPLACE,
        inBuffer: Int = 0,
        outBuffer: Int = 0,
    ) = AvsEffectListConfig(
        enabled = enabled,
        clearFrame = clearFrame,
        input = input,
        output = output,
        inBuffer = inBuffer,
        outBuffer = outBuffer,
    )

    private fun builtin(name: String) = AvsComponent.Builtin(0, AvsComponentType(name, "Trans"), ByteArray(0))

    private fun ape(name: String) = AvsComponent.Ape(name, ByteArray(0))

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
        val INK = 0xFF204060.toInt()
        val INK_INVERTED = 0xFFDFBF9F.toInt()

        // ids from AvsComponents
        const val ON_BEAT_CLEAR = 5
        const val INVERT = 37
        const val BUFFER_SAVE = 18

        /** A Color Reduction body opens with 260 unused bytes of Windows MAX_PATH. */
        const val APE_PATH_PADDING = 260
    }
}
