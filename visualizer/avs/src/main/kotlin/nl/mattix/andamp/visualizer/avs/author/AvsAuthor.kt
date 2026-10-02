// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs.author

import nl.mattix.andamp.visualizer.avs.AvsBlendMode
import nl.mattix.andamp.visualizer.avs.AvsComponent
import nl.mattix.andamp.visualizer.avs.AvsComponents
import nl.mattix.andamp.visualizer.avs.AvsEffectListConfig
import nl.mattix.andamp.visualizer.avs.AvsPreset
import nl.mattix.andamp.visualizer.avs.author.AvsWriter.APE_NAME_SIZE

/**
 * Assembles [AvsComponent] trees whose bytes [AvsWriter] can write and
 * `AvsParser` will read back identically.
 *
 * [effectList] composes an Effect List's body, which is its config block
 * followed by its serialized children: the inverse of `AvsParser.effectList`
 * and `AvsParser.effectListConfig`, at the same fixed offsets. Every other
 * component takes a body the caller supplies, usually built with a
 * [BodyWriter].
 */
object AvsAuthor {
    fun preset(
        vararg components: AvsComponent,
        clearEveryFrame: Boolean = false,
    ): AvsPreset = AvsPreset(clearEveryFrame, components.toList())

    /** One of AVS's own components, by the id `AvsComponents` knows it under. */
    fun builtin(
        id: Int,
        body: ByteArray,
    ): AvsComponent.Builtin =
        AvsComponent.Builtin(
            id,
            requireNotNull(AvsComponents[id]) {
                "no built-in component with id $id"
            },
            body,
        )

    /** A third-party component, named in the 32-byte block the format gives it. */
    fun ape(
        name: String,
        body: ByteArray,
    ): AvsComponent.Ape {
        val encoded = name.toByteArray(Charsets.ISO_8859_1)
        require(encoded.size <= APE_NAME_SIZE) { "an APE name block is $APE_NAME_SIZE bytes; '$name' is ${encoded.size}" }
        require(encoded.none { it.toInt() == 0 }) { "an APE name stops at its first null; '$name' would not read back whole" }
        return AvsComponent.Ape(name, body)
    }

    /**
     * The one component that nests. Its body (the config block plus the
     * children's records) is computed here, and [AvsWriter] writes it verbatim.
     */
    fun effectList(
        vararg components: AvsComponent,
        config: AvsEffectListConfig = AvsEffectListConfig(),
    ): AvsComponent.EffectList {
        val children = components.toList()
        return AvsComponent.EffectList(config, children, configBlock(config) + AvsWriter.componentStream(children))
    }

    /**
     * The 37-byte config block at an Effect List body's fixed offsets: flags,
     * an unused byte, the two blend codes, the size byte, then the eight
     * extended int32s. The mode bit is always set; without it the parser
     * reads the flags byte itself as the size.
     */
    private fun configBlock(config: AvsEffectListConfig): ByteArray =
        BodyWriter()
            .byte(flagsOf(config))
            .byte(0)
            .byte(incomingCode(config.input))
            // the file stores the outgoing mode as its incoming code XOR 1;
            // AvsParser undoes it the same way (AvsBlendMode.outgoing)
            .byte(incomingCode(config.output) xor 1)
            .byte(CONFIG_SIZE - 1)
            .int32(config.inAdjust)
            .int32(config.outAdjust)
            .int32(config.inBuffer)
            .int32(config.outBuffer)
            .int32(bool(config.inBufferInvert))
            .int32(bool(config.outBufferInvert))
            .int32(bool(config.onlyOnBeat))
            .int32(config.onBeatFrames)
            .toByteArray()

    private fun flagsOf(config: AvsEffectListConfig): Int =
        MODE_BIT or
            (if (config.clearFrame) CLEAR_FRAME_BIT else 0) or
            (if (config.enabled) 0 else DISABLED_BIT)

    /**
     * The code for a mode, found by searching `AvsBlendMode.incoming`, which
     * holds the only copy of the table.
     */
    private fun incomingCode(mode: AvsBlendMode): Int = AvsBlendMode.entries.indices.first { AvsBlendMode.incoming(it) == mode }

    private fun bool(value: Boolean): Int = if (value) 1 else 0

    private const val MODE_BIT = 0x80
    private const val CLEAR_FRAME_BIT = 0x01
    private const val DISABLED_BIT = 0x02

    /** Five header bytes and eight extended int32s. */
    private const val CONFIG_SIZE = 37
}
