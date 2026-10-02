// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Super Scope's config, on bytes built here. What the scope draws needs the
 * evaluator and is tested on a device.
 */
class SuperScopeReaderTest {
    @Test
    fun `the four code sections are read from their stored order`() {
        // stored per-point, per-frame, on-beat, init
        val config = SuperScopeReader.read(scope(code = listOf("point", "frame", "beat", "init")))!!

        assertEquals("init", config.init)
        assertEquals("frame", config.perFrame)
        assertEquals("beat", config.onBeat)
        assertEquals("point", config.perPoint)
    }

    @Test
    fun `an empty section reads as an empty string`() {
        val config = SuperScopeReader.read(scope(code = listOf("x=i", "", "", "")))!!

        assertEquals("x=i", config.perPoint)
        assertEquals("", config.init)
    }

    @Test
    fun `the audio source and channel come out of one bitfield`() {
        assertEquals(AvsAudioSource.WAVEFORM, SuperScopeReader.read(scope(flags = 0))!!.source)
        assertEquals(AvsAudioSource.SPECTRUM, SuperScopeReader.read(scope(flags = 0x04))!!.source)
        assertEquals(AvsAudioChannel.LEFT, SuperScopeReader.read(scope(flags = 0))!!.channel)
        assertEquals(AvsAudioChannel.RIGHT, SuperScopeReader.read(scope(flags = 1))!!.channel)
        assertEquals(AvsAudioChannel.CENTER, SuperScopeReader.read(scope(flags = 2))!!.channel)
    }

    /**
     * The stored int comes through raw: it becomes the `drawmode` variable, and
     * AVS draws lines for anything nonzero, so a file holding 2 is a lines scope.
     */
    @Test
    fun `the draw mode is the stored int, not a boolean`() {
        assertEquals(1, SuperScopeReader.read(scope(drawMode = 1))!!.drawMode)
        assertEquals(0, SuperScopeReader.read(scope(drawMode = 0))!!.drawMode)
        assertEquals(2, SuperScopeReader.read(scope(drawMode = 2))!!.drawMode)
    }

    /** A config writes `0x00RRGGBB`, which is Android's channel order. */
    @Test
    fun `colors keep their channel order and are made opaque`() {
        // (A)RGB in the file and on screen; only the alpha is added
        val config = SuperScopeReader.read(scope(colours = listOf(0xFF0000, 0x0000FF)))!!

        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), config.colours)
    }

    /** AVS returns from render before running any code when the palette is empty. */
    @Test
    fun `a scope with no colors keeps none`() {
        assertEquals(emptyList<Int>(), SuperScopeReader.read(scope(colours = emptyList()))!!.colours)
    }

    /**
     * AVS's loader refuses a count past 16 without consuming the color ints,
     * so the next field, the draw mode, reads from where the first color
     * begins (e_superscope.cpp:295-301). The reader does the same.
     */
    @Test
    fun `a color count past sixteen loads no colors and consumes none of their bytes`() {
        var body = byteArrayOf(1)
        repeat(4) { body += int32(0) } // four empty code sections
        body += int32(0) // flags
        body += int32(17) // one color too many
        repeat(17) { body += int32(0x123456) } // the colors AVS does not consume
        body += int32(1) // the real draw mode, which AVS does not reach

        val config = SuperScopeReader.read(body)!!

        assertEquals(emptyList<Int>(), config.colours)
        assertEquals("the draw mode is read from the first unconsumed color", 0x123456, config.drawMode)
    }

    @Test
    fun `a negative color count loads no colors`() {
        var body = byteArrayOf(1)
        repeat(4) { body += int32(0) }
        body += int32(0)
        body += int32(-1) // uint32 4294967295 in AVS: far past 16
        body += int32(7)

        val config = SuperScopeReader.read(body)!!

        assertEquals(emptyList<Int>(), config.colours)
        assertEquals(7, config.drawMode)
    }

    /** The older layout has no version byte and four fixed 256-byte slots. */
    @Test
    fun `the legacy layout is read too`() {
        val config = SuperScopeReader.read(legacyScope(listOf("point", "frame", "beat", "init")))!!

        assertEquals("init", config.init)
        assertEquals("point", config.perPoint)
    }

    @Test
    fun `a body that runs out mid-field is null`() {
        assertNull(SuperScopeReader.read(byteArrayOf(1, 0, 0)))
        assertNull(SuperScopeReader.read(ByteArray(0)))
    }

    /** For a length near the top of an Int, an end offset would wrap around to something small. */
    @Test
    fun `a code section with an impossible length is null`() {
        listOf(Int.MAX_VALUE, Int.MAX_VALUE - 3, 0x7FFFFFF0, -1).forEach { length ->
            assertNull("length $length", SuperScopeReader.read(byteArrayOf(1) + int32(length) + ByteArray(64)))
        }
    }

    private fun scope(
        code: List<String> = listOf("", "", "", ""),
        flags: Int = 0,
        colours: List<Int> = listOf(0xFFFFFF),
        drawMode: Int = 1,
    ): ByteArray {
        var out = byteArrayOf(1)
        code.forEach { out += int32(it.length) + it.toByteArray(Charsets.ISO_8859_1) }
        out += int32(flags)
        out += int32(colours.size)
        colours.forEach { out += int32(it) }
        return out + int32(drawMode)
    }

    private fun legacyScope(code: List<String>): ByteArray {
        var out = ByteArray(0)
        code.forEach { section ->
            val slot = ByteArray(LEGACY_SLOT)
            section.toByteArray(Charsets.ISO_8859_1).copyInto(slot)
            out += slot
        }
        return out + int32(0) + int32(1) + int32(0xFFFFFF) + int32(1)
    }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        const val LEGACY_SLOT = 256
    }
}
