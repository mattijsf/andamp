// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import nl.mattix.andamp.backend.media3.dsp.DspSettings
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The DSP slot: off by default, audible when switched on, and ordered. */
class DspAudioProcessorTest {
    private fun stereo(frames: Int): ByteBuffer =
        ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                val v = (8000 * kotlin.math.sin(it / 10.0)).toInt().toShort()
                putShort(v)
                putShort(v)
            }
            flip()
        }

    /**
     * Long enough for a control to arrive where it was put: the rack fades a
     * newly switched-on effect in and glides its controls to their values, so
     * a few dozen frames would measure the fade.
     */
    private val settled = 20_000

    /** A steady stereo tone, both channels equal, long enough for effects to settle. */
    private fun steadyStereo(frames: Int): ByteBuffer =
        ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                val v = (8000 * kotlin.math.sin(it / 7.0)).toInt().toShort()
                putShort(v)
                putShort(v)
            }
            flip()
        }

    /** A rack of the named effects, all switched on, in the order given. */
    private fun rackOf(vararg pluginIds: String) =
        RackSettings(pluginIds.map { RackSlot(it, enabled = true, params = BuiltInEffects.byId(it)!!.defaults) })

    private fun run(
        rack: RackSettings,
        input: ByteBuffer = stereo(512),
    ): List<Short> {
        val processor = DspAudioProcessor().apply { update(rack) }
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return buildList { while (out.remaining() >= 2) add(out.short) }
    }

    @Test
    fun `with nothing enabled the samples pass through untouched`() {
        val input = stereo(64)
        val expected =
            buildList {
                input.duplicate().order(ByteOrder.LITTLE_ENDIAN).let {
                    while (it.remaining() >=
                        2
                    ) {
                        add(it.short)
                    }
                }
            }

        assertEquals(expected, run(RackSettings.EMPTY, input))
    }

    @Test
    fun `every effect in the rack changes the audio once switched on`() {
        val dry = run(RackSettings.EMPTY)

        BuiltInEffects.ids.forEach { id ->
            val wet = run(rackOf(id))
            assertEquals(dry.size, wet.size)
            assertNotEquals("$id changes the audio when switched on", dry, wet)
        }
    }

    @Test
    fun `the rack order is the signal order`() {
        val roomThenVoice = rackOf(BuiltInEffects.REVERB, BuiltInEffects.KARAOKE)
        val voiceThenRoom = rackOf(BuiltInEffects.KARAOKE, BuiltInEffects.REVERB)

        assertNotEquals(
            "reverb then karaoke differs from karaoke then reverb",
            run(roomThenVoice, steadyStereo(settled)),
            run(voiceThenRoom, steadyStereo(settled)),
        )
    }

    @Test
    fun `a disabled slot is bit-identical to not being in the rack at all`() {
        val alone = rackOf(BuiltInEffects.REVERB)
        val withCompany =
            RackSettings(
                alone.slots + RackSlot(BuiltInEffects.MODULATION, enabled = false, params = BuiltInEffects.modulation.defaults),
            )

        assertEquals(run(alone), run(withCompany))
    }

    @Test
    fun `each modulation mode sounds different from the others`() {
        val outputs =
            DspSettings.Mode.entries.associateWith { mode ->
                run(rackOf(BuiltInEffects.MODULATION).setValue(BuiltInEffects.MODULATION, "mode", mode.ordinal.toFloat()))
            }

        assertEquals("the three modes give three different outputs", 3, outputs.values.distinct().size)
    }

    /**
     * The rack is changed from another thread while the audio thread is
     * running it. What was asked for last is what is heard once the changes
     * stop, and nothing in between throws.
     */
    @Test
    fun `a rack changed while it plays ends on what was asked for last`() {
        val on = rackOf(BuiltInEffects.ids.first())
        val processor = DspAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val failed =
            java.util.concurrent.atomic
                .AtomicReference<Throwable?>(null)
        val changing =
            Thread {
                try {
                    repeat(400) { processor.update(if (it % 2 == 0) on else RackSettings.EMPTY) }
                    processor.update(on)
                } catch (thrown: Throwable) {
                    failed.set(thrown)
                }
            }
        changing.start()
        while (changing.isAlive) {
            processor.queueInput(apart(256))
            processor.output
        }
        changing.join()
        assertEquals(null, failed.get())

        processor.queueInput(apart(settled))
        processor.output
        processor.queueInput(apart(512))
        val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val heard = buildList { while (out.remaining() >= 2) add(out.short) }
        val dry = apart(512).let { buildList { while (it.remaining() >= 2) add(it.short) } }

        assertNotEquals("the effect asked for last is what is playing", dry, heard)
    }

    /** Stereo with the two channels apart, so an effect that works on their difference has something to change. */
    private fun apart(frames: Int): ByteBuffer =
        ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                putShort((8000 * kotlin.math.sin(it / 7.0)).toInt().toShort())
                putShort((6000 * kotlin.math.sin(it / 11.0)).toInt().toShort())
            }
            flip()
        }

    @Test
    fun `an empty input produces no output and does not throw`() {
        val processor = DspAudioProcessor().apply { update(rackOf(BuiltInEffects.REVERB)) }
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        processor.queueInput(AudioProcessor.EMPTY_BUFFER)

        assertEquals(0, processor.output.remaining())
    }

    @Test
    fun `a format it cannot speak bypasses instead of mangling the stream`() {
        val processor = DspAudioProcessor()

        val format = processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))

        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
        assertTrue(!processor.isActive)
    }

    /**
     * The loader's thread ends when idle, so a processor that is dropped does
     * not leave a thread behind.
     */
    @Test
    fun `the plug-in thread goes once it has nothing to do`() {
        val loader = pluginLoader(idleMs = 20)
        val ran = CountDownLatch(1)

        loader.execute { ran.countDown() }

        assertTrue("the loader runs the script", ran.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (loader.poolSize > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("an idle loader ends its thread", 0, loader.poolSize)
    }
}
