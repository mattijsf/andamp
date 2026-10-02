// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing, on bytes built here. Real presets are the corpus test's job; here a
 * failure names a field.
 */
class AvsParserTest {
    @Test
    fun `a preset is its clear-every-frame flag and its components`() {
        val preset = AvsParser.parse(preset(clearEveryFrame = true, body = builtin(BLUR)))

        assertTrue(preset.clearEveryFrame)
        assertEquals(listOf("Blur"), preset.components.map { it.name })
    }

    @Test
    fun `not clearing is the other value of the same byte`() {
        assertEquals(false, AvsParser.parse(preset(clearEveryFrame = false, body = ByteArray(0))).clearEveryFrame)
    }

    @Test
    fun `components come back in the order the preset runs them`() {
        val preset = AvsParser.parse(preset(body = builtin(BLUR) + builtin(INVERT) + builtin(FADE_OUT)))

        assertEquals(listOf("Blur", "Invert", "FadeOut"), preset.components.map { it.name })
    }

    @Test
    fun `a component keeps its body`() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val parsed = AvsParser.parse(preset(body = builtin(BLUR, body))).components.single()

        assertTrue(body.contentEquals(parsed.body))
    }

    @Test
    fun `an effect list holds its children`() {
        val list = effectList(builtin(BLUR) + builtin(INVERT))
        val parsed = AvsParser.parse(preset(body = list)).components.single()

        assertTrue(parsed is AvsComponent.EffectList)
        assertEquals(listOf("Blur", "Invert"), parsed.children().map { it.name })
    }

    @Test
    fun `effect lists nest as deep as the preset does`() {
        val inner = effectList(builtin(BLUR))
        val outer = effectList(inner + builtin(INVERT))

        val parsed = AvsParser.parse(preset(body = outer)).components.single()

        assertEquals(listOf("Effect List", "Invert"), parsed.children().map { it.name })
        assertEquals(
            listOf("Blur"),
            parsed
                .children()
                .first()
                .children()
                .map { it.name },
        )
    }

    /** A 2.8+ list carries an ns-eel snippet before its children; it is not a child. */
    @Test
    fun `an effect list's own code block is not framed as a component`() {
        val code = "n=0;".toByteArray()
        val list = effectList(builtin(BLUR), listCode = code)

        val parsed = AvsParser.parse(preset(body = list)).components.single()

        assertEquals(listOf("Blur"), parsed.children().map { it.name })
    }

    /** A third-party component names itself in 32 bytes instead of taking an id. */
    @Test
    fun `an APE is identified by the name it carries`() {
        val parsed = AvsParser.parse(preset(body = ape("Texer II"))).components.single()

        assertTrue(parsed is AvsComponent.Ape)
        assertEquals("Texer II", parsed.name)
    }

    @Test
    fun `an APE beside built-ins does not throw the stream out of step`() {
        val preset = AvsParser.parse(preset(body = builtin(BLUR) + ape("Texer II") + builtin(INVERT)))

        assertEquals(listOf("Blur", "Texer II", "Invert"), preset.components.map { it.name })
    }

    @Test
    fun `an unknown built-in id parses as Unknown`() {
        val parsed = AvsParser.parse(preset(body = builtin(200))).components.single()

        assertTrue(parsed is AvsComponent.Unknown)
        assertEquals("Unknown(200)", parsed.name)
    }

    @Test
    fun `a file that is not an AVS preset says so`() {
        val thrown =
            assertThrows(AvsFormatException::class.java) {
                AvsParser.parse("Nullsoft AVS Preset 9.9".toByteArray() + ByteArray(8))
            }
        assertTrue(thrown.message!!.contains("not an AVS preset"))
    }

    @Test
    fun `version 0_1 frames the same way as 0_2`() {
        val bytes = preset(body = builtin(BLUR))
        "Nullsoft AVS Preset 0.1".toByteArray().copyInto(bytes)

        assertEquals(listOf("Blur"), AvsParser.parse(bytes).components.map { it.name })
    }

    /**
     * Some presets carry trailing bytes. Fewer than two int32s left is the end
     * of the stream.
     */
    @Test
    fun `trailing bytes too short to be a component are left alone`() {
        val preset = AvsParser.parse(preset(body = builtin(BLUR) + byteArrayOf(7, 7, 7)))

        assertEquals(listOf("Blur"), preset.components.map { it.name })
    }

    /** An APE header is a code and a 32-byte name; this file ends inside the name. */
    @Test
    fun `a component cut off mid-header is a format error`() {
        val bytes = preset(body = int32(AvsComponents.APE_MIN + 1) + ByteArray(10))

        val thrown = assertThrows(AvsFormatException::class.java) { AvsParser.parse(bytes) }

        assertTrue(thrown.message, thrown.message!!.contains("cut off"))
    }

    @Test
    fun `a component whose size runs past the file is refused with what it claimed`() {
        val bytes = preset(body = int32(BLUR) + int32(9_000))

        val thrown = assertThrows(AvsFormatException::class.java) { AvsParser.parse(bytes) }

        assertTrue(thrown.message, thrown.message!!.contains("9000"))
    }

    /**
     * A negative code block length would put the children back at the list's
     * own header, making the list its own child.
     */
    @Test
    fun `an effect list whose code block has a negative length is refused`() {
        val bytes = preset(body = hostileList(codeLength = -44))

        val thrown = assertThrows(AvsFormatException::class.java) { AvsParser.parse(bytes) }

        assertTrue(thrown.message, thrown.message!!.contains("code block"))
    }

    /** A length near the top of an Int would wrap around to the same place. */
    @Test
    fun `an effect list whose code block is longer than its body is refused`() {
        listOf(Int.MAX_VALUE, Int.MAX_VALUE - 40, 9_000).forEach { length ->
            assertThrows(AvsFormatException::class.java) { AvsParser.parse(preset(body = hostileList(length))) }
        }
    }

    /** Each level is a stack frame, and a file can be written with more levels than there is stack. */
    @Test
    fun `effect lists nested a hundred deep are refused`() {
        var nested = builtin(BLUR)
        repeat(100) { nested = effectList(nested) }

        val thrown = assertThrows(AvsFormatException::class.java) { AvsParser.parse(preset(body = nested)) }

        assertTrue(thrown.message, thrown.message!!.contains("nested"))
    }

    /** An Effect List with the 2.8+ marker and a code length that is not the code's. */
    private fun hostileList(codeLength: Int): ByteArray {
        val honest = effectList(builtin(BLUR), listCode = ByteArray(0))
        val lengthAt = INT_SIZE * 2 + CONFIG_SIZE + MARKER_SIZE
        return honest.copyOf().also { int32(codeLength).copyInto(it, lengthAt) }
    }

    @Test
    fun `an effect list's own settings are read`() {
        val list = effectList(builtin(BLUR), flags = MODE_BIT or 0x01, input = 12, output = 13)

        val parsed = AvsParser.parse(preset(body = list)).components.single() as AvsComponent.EffectList

        assertTrue(parsed.config.enabled)
        assertTrue(parsed.config.clearFrame)
        assertEquals(AvsBlendMode.BUFFER, parsed.config.input)
        assertEquals(AvsBlendMode.BUFFER, parsed.config.output)
    }

    /** Bit 1 is set when a list is switched off. */
    @Test
    fun `a disabled effect list says so`() {
        val list = effectList(builtin(BLUR), flags = MODE_BIT or 0x02)

        val parsed = AvsParser.parse(preset(body = list)).components.single() as AvsComponent.EffectList

        assertEquals(false, parsed.config.enabled)
    }

    @Test
    fun `flatten walks the whole tree`() {
        val preset = AvsParser.parse(preset(body = effectList(builtin(BLUR) + builtin(INVERT)) + builtin(FADE_OUT)))

        assertEquals(listOf("Effect List", "Blur", "Invert", "FadeOut"), preset.flatten().map { it.name })
    }

    private fun preset(
        clearEveryFrame: Boolean = false,
        body: ByteArray,
    ) = "Nullsoft AVS Preset 0.2".toByteArray(Charsets.ISO_8859_1) +
        byteArrayOf(if (clearEveryFrame) 1 else 0) +
        body

    private fun builtin(
        id: Int,
        body: ByteArray = ByteArray(0),
    ) = int32(id) + int32(body.size) + body

    private fun ape(
        name: String,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        val padded = ByteArray(APE_NAME_SIZE)
        name.toByteArray(Charsets.ISO_8859_1).copyInto(padded)
        return int32(AvsComponents.APE_MIN + 1) + padded + int32(body.size) + body
    }

    /**
     * An Effect List body as the format writes one: the mode bit set, a config
     * whose length is the fifth byte, then optionally the 2.8+ code block, then
     * the children.
     */
    private fun effectList(
        children: ByteArray,
        listCode: ByteArray? = null,
        flags: Int = MODE_BIT,
        input: Int = 0,
        output: Int = 0,
    ): ByteArray {
        val config = ByteArray(CONFIG_SIZE)
        config[0] = flags.toByte()
        config[2] = input.toByte()
        config[3] = output.toByte()
        config[4] = (CONFIG_SIZE - 1).toByte()
        // the 36 marker bytes written out from AVS-File-Decoder's decode.ts,
        // independently of the parser's own constant
        val code =
            listCode?.let {
                byteArrayOf(
                    0x00,
                    0x40,
                    0x00,
                    0x00,
                    0x41,
                    0x56,
                    0x53,
                    0x20,
                    0x32,
                    0x2E,
                    0x38,
                    0x2B,
                    0x20,
                    0x45,
                    0x66,
                    0x66,
                    0x65,
                    0x63,
                    0x74,
                    0x20,
                    0x4C,
                    0x69,
                    0x73,
                    0x74,
                    0x20,
                    0x43,
                    0x6F,
                    0x6E,
                    0x66,
                    0x69,
                    0x67,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                ) + int32(it.size) + it
            } ?: ByteArray(0)
        val body = config + code + children
        return int32(AvsComponents.EFFECT_LIST) + int32(body.size) + body
    }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        // ids from AvsComponents
        const val BLUR = 6
        const val INVERT = 37
        const val FADE_OUT = 3
        const val APE_NAME_SIZE = 32
        const val MODE_BIT = 0x80
        const val CONFIG_SIZE = 37
        const val INT_SIZE = 4

        /** The 2.8+ marker [effectList] writes ahead of a code block. */
        const val MARKER_SIZE = 36
    }
}
