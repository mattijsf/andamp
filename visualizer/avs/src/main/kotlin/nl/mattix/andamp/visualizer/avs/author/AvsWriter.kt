// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs.author

import nl.mattix.andamp.visualizer.avs.AvsComponent
import nl.mattix.andamp.visualizer.avs.AvsComponents
import nl.mattix.andamp.visualizer.avs.AvsPreset

/**
 * Writes the `.avs` binary format `AvsParser` reads: the 24-byte header, the
 * clear-every-frame byte, then each component as an int32 id, a 32-byte name if
 * the id says APE, an int32 size, and the body.
 *
 * Bodies are written verbatim; [AvsAuthor] makes them, or the parser for a
 * preset read from disk. A preset that is parsed and written again therefore
 * keeps its bodies byte for byte: an Effect List that carried a 2.8+ code
 * block keeps it.
 */
object AvsWriter {
    /** The fixed name block an APE carries after its id. */
    internal const val APE_NAME_SIZE = 32

    /** 23 ASCII characters and the 0x1A terminator the parser checks. */
    private val HEADER = "Nullsoft AVS Preset 0.2\u001A".toByteArray(Charsets.ISO_8859_1)

    fun write(preset: AvsPreset): ByteArray =
        BodyWriter()
            .bytes(HEADER)
            .byte(if (preset.clearEveryFrame) 1 else 0)
            .bytes(componentStream(preset.components))
            .toByteArray()

    /** The back-to-back records that a preset's top level and an Effect List's interior both are. */
    internal fun componentStream(components: List<AvsComponent>): ByteArray {
        val writer = BodyWriter()
        components.forEach { record(writer, it) }
        return writer.toByteArray()
    }

    private fun record(
        writer: BodyWriter,
        component: AvsComponent,
    ) {
        when (component) {
            is AvsComponent.Builtin -> {
                plain(writer, component.id, component.body)
            }

            is AvsComponent.Unknown -> {
                plain(writer, component.id, component.body)
            }

            is AvsComponent.EffectList -> {
                writer
                    .int32(AvsComponents.EFFECT_LIST)
                    .int32(component.body.size)
                    .bytes(component.body)
            }

            // any code at or above APE_MIN will do: the parser keeps only the name
            is AvsComponent.Ape -> {
                writer
                    .int32(AvsComponents.APE_MIN)
                    .fixedString(component.name, APE_NAME_SIZE)
                    .int32(component.body.size)
                    .bytes(component.body)
            }
        }
    }

    private fun plain(
        writer: BodyWriter,
        id: Int,
        body: ByteArray,
    ) {
        require(id != AvsComponents.EFFECT_LIST && id < AvsComponents.APE_MIN) {
            "id $id would frame back as an Effect List or an APE, not as itself"
        }
        writer.int32(id).int32(body.size).bytes(body)
    }
}
