// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * What a component is: the name AVS's own editor shows, and the group it sits
 * under (Render, Trans, Misc).
 */
data class AvsComponentType(
    val name: String,
    val group: String,
)

/**
 * AVS's built-in component ids.
 *
 * Transcribed from grandchild/AVS-File-Decoder (MIT; see NOTICE.md), which is
 * the reference for the ids.
 *
 * Ids are dense from 0; anything at or above [APE_MIN] is a third-party
 * component that names itself instead (see [AvsComponent.Ape]).
 */
object AvsComponents {
    /** Effect List, the one component that nests. `0xFFFFFFFE` read as a signed int32. */
    const val EFFECT_LIST = -2

    /** At or above this, a component is an APE and carries a 32-byte name. */
    const val APE_MIN = 16_384

    private val byId: Map<Int, AvsComponentType> =
        mapOf(
            0 to AvsComponentType("Simple", "Render"),
            1 to AvsComponentType("Dot Plane", "Render"),
            2 to AvsComponentType("Oscilliscope Star", "Render"),
            3 to AvsComponentType("FadeOut", "Trans"),
            4 to AvsComponentType("Blitter Feedback", "Misc"),
            5 to AvsComponentType("OnBeat Clear", "Render"),
            6 to AvsComponentType("Blur", "Trans"),
            7 to AvsComponentType("Bass Spin", "Trans"),
            8 to AvsComponentType("Moving Particle", "Render"),
            9 to AvsComponentType("Roto Blitter", "Trans"),
            10 to AvsComponentType("SVP", "Render"),
            11 to AvsComponentType("Colorfade", "Trans"),
            12 to AvsComponentType("Color Clip", "Trans"),
            13 to AvsComponentType("Rotating Stars", "Render"),
            14 to AvsComponentType("Ring", "Render"),
            15 to AvsComponentType("Movement", "Trans"),
            16 to AvsComponentType("Scatter", "Trans"),
            17 to AvsComponentType("Dot Grid", "Render"),
            18 to AvsComponentType("Buffer Save", "Misc"),
            19 to AvsComponentType("Dot Fountain", "Render"),
            20 to AvsComponentType("Water", "Trans"),
            21 to AvsComponentType("Comment", "Misc"),
            22 to AvsComponentType("Brightness", "Trans"),
            23 to AvsComponentType("Interleave", "Trans"),
            24 to AvsComponentType("Grain", "Trans"),
            25 to AvsComponentType("Clear Screen", "Render"),
            26 to AvsComponentType("Mirror", "Trans"),
            27 to AvsComponentType("Starfield", "Render"),
            28 to AvsComponentType("Text", "Render"),
            29 to AvsComponentType("Bump", "Trans"),
            30 to AvsComponentType("Mosaic", "Trans"),
            31 to AvsComponentType("Water Bump", "Trans"),
            32 to AvsComponentType("AVI", "Trans"),
            33 to AvsComponentType("Custom BPM", "Misc"),
            34 to AvsComponentType("Picture", "Render"),
            35 to AvsComponentType("Dynamic Distance Modifier", "Trans"),
            36 to AvsComponentType("Super Scope", "Render"),
            37 to AvsComponentType("Invert", "Trans"),
            38 to AvsComponentType("Unique Tone", "Trans"),
            39 to AvsComponentType("Timescope", "Render"),
            40 to AvsComponentType("Set Render Mode", "Misc"),
            41 to AvsComponentType("Interferences", "Trans"),
            42 to AvsComponentType("Dynamic Shift", "Trans"),
            43 to AvsComponentType("Dynamic Movement", "Trans"),
            44 to AvsComponentType("Fast Brightness", "Trans"),
            45 to AvsComponentType("Color Modifier", "Trans"),
        )

    operator fun get(id: Int): AvsComponentType? = byId[id]

    /** The id of every built-in in the table. */
    val ids: Set<Int> get() = byId.keys
}
