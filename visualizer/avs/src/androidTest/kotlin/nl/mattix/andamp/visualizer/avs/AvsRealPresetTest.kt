// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A preset as a file stores one, through the whole path: parsed, loaded,
 * compiled and drawn.
 *
 * The bytes are built here, because preset packs are not in this repository
 * (NOTICE.md), in the layout the format uses, so this exercises the parser,
 * the Effect List, the reader and the evaluator together.
 */
@RunWith(AndroidJUnit4::class)
class AvsRealPresetTest {
    @Test
    fun a_preset_holding_a_scope_inside_a_list_parses_loads_and_draws() {
        val preset = AvsParser.parse(presetBytes(perPoint = "x = 0; y = i * 2 - 1;", init = "n = $HEIGHT"))

        AvsEngine(WIDTH, HEIGHT).use { engine ->
            engine.load(preset)

            assertEquals("everything in this preset is implemented", emptyList<String>(), engine.unimplemented)

            val frame = engine.render(AvsAudioFrame())
            val middle = WIDTH / 2
            val lit = (0 until HEIGHT).count { frame[middle, it] != AvsFrame.OPAQUE }
            assertTrue("the scope draws more than half of $HEIGHT rows: $lit", lit > HEIGHT / 2)
        }
    }

    @Test
    fun a_preset_that_clears_every_frame_does_not_smear() {
        val preset = AvsParser.parse(presetBytes(perPoint = "x = 0; y = 0;", init = "n = 1", clearEveryFrame = true))

        AvsEngine(WIDTH, HEIGHT).use { engine ->
            engine.load(preset)
            engine.render()
            val drawn = engine.frame.pixels.count { it != AvsFrame.OPAQUE }

            engine.render()

            assertEquals(
                "a cleared frame draws the same amount again",
                drawn,
                engine.frame.pixels.count {
                    it !=
                        AvsFrame.OPAQUE
                },
            )
        }
    }

    /** A Blur with an empty body gives no renderer, so it is listed, and the scope still draws. */
    @Test
    fun a_preset_with_a_component_that_cannot_be_built_still_draws_the_rest() {
        val bytes = presetBytes(perPoint = "x = 0; y = i * 2 - 1;", init = "n = $HEIGHT", extra = builtin(BLUR))
        val preset = AvsParser.parse(bytes)

        AvsEngine(WIDTH, HEIGHT).use { engine ->
            engine.load(preset)

            assertEquals(listOf("Blur"), engine.unimplemented)
            assertTrue(engine.render().pixels.any { it != AvsFrame.OPAQUE })
        }
    }

    private fun presetBytes(
        perPoint: String,
        init: String = "",
        clearEveryFrame: Boolean = false,
        extra: ByteArray = ByteArray(0),
    ): ByteArray {
        val scope = superScope(perPoint = perPoint, init = init)
        val list = effectList(scope + extra)
        // the trailing 0x1A is part of the header
        return HEADER.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(if (clearEveryFrame) 1 else 0) +
            list
    }

    private fun superScope(
        perPoint: String,
        init: String,
    ): ByteArray {
        var body = byteArrayOf(1)
        // stored order: per point, per frame, on beat, init
        listOf(perPoint, "", "", init).forEach { body += int32(it.length) + it.toByteArray(Charsets.ISO_8859_1) }
        body += int32(0) // audio channel and source
        body += int32(1) + int32(0xFFFFFF) // one white color
        body += int32(0) // dots
        return builtin(SUPER_SCOPE, body)
    }

    private fun effectList(children: ByteArray): ByteArray {
        val config = ByteArray(CONFIG_SIZE)
        config[0] = MODE_BIT.toByte()
        config[4] = (CONFIG_SIZE - 1).toByte()
        val body = config + children
        return int32(EFFECT_LIST) + int32(body.size) + body
    }

    private fun builtin(
        id: Int,
        body: ByteArray = ByteArray(0),
    ) = int32(id) + int32(body.size) + body

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        const val HEADER = "Nullsoft AVS Preset 0.2\u001A"
        const val WIDTH = 33
        const val HEIGHT = 33
        const val SUPER_SCOPE = 0x24
        const val BLUR = 6
        const val EFFECT_LIST = -2
        const val CONFIG_SIZE = 36
        const val MODE_BIT = 0x80
    }
}
