// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interferences, against e_interferences.cpp's behavior on known pictures. */
class InterferencesTest {
    @Test
    fun `one layer at distance zero and full alpha is the picture itself`() {
        val frame = AvsFrame(8, 8)
        frame[3, 3] = 0xFF804020.toInt()

        renderer(layers = 1, distance = 0, alpha = 255).render(frame, AvsAudioFrame(), state())

        assertEquals(0xFF804020.toInt(), frame[3, 3])
    }

    @Test
    fun `two layers at half alpha sum back to the original brightness`() {
        val frame = AvsFrame(8, 8)
        frame.pixels.fill(0xFF808080.toInt())

        // both copies land everywhere (distance 0), each at 127/255
        renderer(layers = 2, distance = 0, alpha = 127).render(frame, AvsAudioFrame(), state())

        val value = frame[4, 4] and 0xFF
        assertTrue("the brightness is near 0x7F: $value", value in 0x7C..0x80)
    }

    @Test
    fun `layers at a distance shift their copy of the picture`() {
        val frame = AvsFrame(16, 16)
        frame[8, 8] = 0xFFFFFFFF.toInt()

        // one layer, rotation 0 -> offset (distance, 0): the dot moves right
        renderer(layers = 1, distance = 4, alpha = 255).render(frame, AvsAudioFrame(), state())

        assertEquals(0xFFFFFFFF.toInt(), frame[12, 8])
        assertEquals(AvsFrame.OPAQUE, frame[8, 8])
    }

    @Test
    fun `separate rgb with three layers splits the channels`() {
        val frame = AvsFrame(8, 8)
        frame.pixels.fill(0xFFFFFFFF.toInt())

        renderer(layers = 3, distance = 0, alpha = 255, separateRgb = true).render(frame, AvsAudioFrame(), state())

        // each channel is fed by one white layer at full alpha: white again
        assertEquals(0xFFFFFFFF.toInt(), frame[4, 4])
    }

    @Test
    fun `zero layers leaves the frame alone`() {
        val frame = AvsFrame(4, 4)
        frame.pixels.fill(0xFF123456.toInt())

        renderer(layers = 0, distance = 9, alpha = 9).render(frame, AvsAudioFrame(), state())

        assertTrue(frame.pixels.all { it == 0xFF123456.toInt() })
    }

    @Test
    fun `a body that runs out is null`() {
        assertNull(InterferencesRenderer.read(ByteArray(6)))
    }

    @Test
    fun `the reader accepts a body of fourteen fields`() {
        val body =
            int32(1) + int32(2) + int32(0) + int32(3) + int32(200) + int32(0) +
                int32(0) + int32(1) + // blend: not additive, fifty-fifty
                int32(0) + int32(0) + int32(0) + int32(0) + int32(0) +
                java.nio.ByteBuffer
                    .allocate(4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putFloat(0.1f)
                    .array()

        val renderer = InterferencesRenderer.read(body)

        assertTrue(renderer != null)
    }

    private fun renderer(
        layers: Int,
        distance: Int,
        alpha: Int,
        separateRgb: Boolean = false,
    ) = InterferencesRenderer(
        InterferencesConfig(
            enabled = true,
            layers = layers,
            initRotation = 0,
            distance = distance,
            alpha = alpha,
            rotation = 0,
            blend = AvsBlendMode.REPLACE,
            onBeatDistance = distance,
            onBeatAlpha = alpha,
            onBeatRotation = 0,
            separateRgb = separateRgb,
            onBeat = false,
            onBeatSpeed = 0.1f,
        ),
    )

    private fun state() = AvsRenderState(AvsBuffers(8, 8))

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )
}
