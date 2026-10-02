// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs.author

import nl.mattix.andamp.visualizer.avs.AvsBlendMode
import nl.mattix.andamp.visualizer.avs.AvsComponent
import nl.mattix.andamp.visualizer.avs.AvsComponentType
import nl.mattix.andamp.visualizer.avs.AvsEffectListConfig
import nl.mattix.andamp.visualizer.avs.AvsParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [AvsWriter] writes, `AvsParser` must read back as the same tree:
 * structure, config and bodies byte for byte. The tests go through the parser
 * and do not restate its offsets.
 */
class AvsWriterTest {
    @Test
    fun `a written preset opens with the header and clear byte the parser demands`() {
        val bytes = AvsWriter.write(AvsAuthor.preset(clearEveryFrame = true))

        val header = "Nullsoft AVS Preset 0.2".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x1A, 1)
        assertArrayEquals(header, bytes)
    }

    @Test
    fun `a preset of several components parses back identically`() {
        val preset =
            AvsAuthor.preset(
                AvsAuthor.builtin(BLUR, BodyWriter().int32(3).int32(1).toByteArray()),
                AvsAuthor.builtin(FADE_OUT, BodyWriter().int32(16).colour(0).toByteArray()),
                AvsAuthor.ape("Channel Shift", BodyWriter().int32(1018).int32(1).toByteArray()),
            )

        assertEquals(preset, AvsParser.parse(AvsWriter.write(preset)))
    }

    @Test
    fun `component bodies come back byte for byte`() {
        val body = byteArrayOf(9, 8, 7, 6, 5)
        val preset = AvsAuthor.preset(AvsAuthor.builtin(BLUR, body))

        val parsed = AvsParser.parse(AvsWriter.write(preset)).components.single()

        assertTrue(body.contentEquals(parsed.body))
    }

    @Test
    fun `a nested effect list with extended config round-trips config, children and bytes`() {
        val inner =
            AvsAuthor.effectList(
                AvsAuthor.builtin(BLUR, BodyWriter().int32(2).int32(0).toByteArray()),
                config = AvsEffectListConfig(input = AvsBlendMode.BUFFER, output = AvsBlendMode.MINIMUM),
            )
        val outer =
            AvsAuthor.effectList(
                AvsAuthor.ape("Color Reduction", BodyWriter().fixedString("", 260).int32(4).toByteArray()),
                inner,
                config =
                    AvsEffectListConfig(
                        enabled = true,
                        clearFrame = true,
                        input = AvsBlendMode.ADJUSTABLE,
                        output = AvsBlendMode.SUB_DEST_SRC,
                        inAdjust = 128,
                        outAdjust = 64,
                        inBuffer = 2,
                        outBuffer = 5,
                        inBufferInvert = true,
                        outBufferInvert = true,
                        onlyOnBeat = true,
                        onBeatFrames = 20,
                    ),
            )
        val preset = AvsAuthor.preset(outer)

        val parsed = AvsParser.parse(AvsWriter.write(preset))

        assertEquals(preset, parsed)
        val parsedOuter = parsed.components.single() as AvsComponent.EffectList
        assertTrue(outer.body.contentEquals(parsedOuter.body))
        val parsedInner = parsedOuter.components.last() as AvsComponent.EffectList
        assertTrue(inner.body.contentEquals(parsedInner.body))
    }

    @Test
    fun `every blend mode survives the trip through the config block`() {
        for (mode in AvsBlendMode.entries) {
            val preset = AvsAuthor.preset(AvsAuthor.effectList(config = AvsEffectListConfig(input = mode, output = mode)))

            val parsed = AvsParser.parse(AvsWriter.write(preset)).components.single() as AvsComponent.EffectList

            assertEquals(mode, parsed.config.input)
            assertEquals(mode, parsed.config.output)
        }
    }

    @Test
    fun `a disabled list that clears its frame keeps both flags`() {
        val list = AvsAuthor.effectList(config = AvsEffectListConfig(enabled = false, clearFrame = true))

        val parsed = AvsParser.parse(AvsWriter.write(AvsAuthor.preset(list))).components.single() as AvsComponent.EffectList

        assertEquals(false, parsed.config.enabled)
        assertEquals(true, parsed.config.clearFrame)
    }

    @Test
    fun `an ape keeps its name and its body`() {
        val preset = AvsAuthor.preset(AvsAuthor.ape("Channel Shift", byteArrayOf(1, 2, 3, 4)))

        val parsed = AvsParser.parse(AvsWriter.write(preset)).components.single()

        assertTrue(parsed is AvsComponent.Ape)
        assertEquals("Channel Shift", parsed.name)
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(parsed.body))
    }

    @Test
    fun `an id no build of avs names still round-trips as unknown`() {
        val preset = AvsAuthor.preset(AvsComponent.Unknown(99, byteArrayOf(1, 2, 3)))

        assertEquals(preset, AvsParser.parse(AvsWriter.write(preset)))
    }

    @Test
    fun `a written preset re-parses and re-writes to the same bytes`() {
        val preset =
            AvsAuthor.preset(
                AvsAuthor.effectList(
                    AvsAuthor.builtin(BLUR, BodyWriter().int32(3).int32(1).toByteArray()),
                    config = AvsEffectListConfig(onlyOnBeat = true, onBeatFrames = 10),
                ),
                AvsAuthor.builtin(FADE_OUT, BodyWriter().int32(8).colour(0).toByteArray()),
                clearEveryFrame = true,
            )

        val written = AvsWriter.write(preset)
        val rewritten = AvsWriter.write(AvsParser.parse(written))

        assertArrayEquals(written, rewritten)
    }

    @Test
    fun `the builder refuses an id the format does not know`() {
        assertThrows(IllegalArgumentException::class.java) { AvsAuthor.builtin(99, ByteArray(0)) }
    }

    @Test
    fun `an id that would frame back as an ape cannot be written as a plain component`() {
        val forged = AvsComponent.Builtin(16_384, AvsComponentType("Forged", "Render"), ByteArray(0))

        assertThrows(IllegalArgumentException::class.java) { AvsWriter.write(AvsAuthor.preset(forged)) }
    }

    private companion object {
        const val FADE_OUT = 3
        const val BLUR = 6
    }
}
