// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/** A named EQ setting; values on the Winamp slider scale 0..63, where [EqOps.CENTER] is 0 dB. */
data class EqPreset(
    val name: String,
    val preamp: Int,
    val bands: List<Int>,
)

/**
 * The 17 factory presets from Winamp's winamp.q1, in file order. Transcribed from webamp's
 * packages/webamp/presets/builtin.json (the parsed q1); its 1..64 EQF scale maps to the
 * 0..63 slider scale as value - 1.
 */
object EqFactoryPresets {
    val ALL: List<EqPreset> =
        listOf(
            preset("Classical", 32, 32, 32, 32, 32, 32, 32, 19, 19, 19, 15),
            preset("Club", 32, 32, 32, 37, 41, 41, 41, 37, 32, 32, 32),
            preset("Dance", 32, 47, 43, 35, 31, 31, 21, 19, 19, 31, 31),
            preset("Laptop speakers/headphones", 32, 39, 49, 40, 25, 27, 34, 39, 47, 52, 55),
            preset("Large hall", 32, 48, 48, 41, 41, 32, 23, 23, 23, 32, 32),
            preset("Party", 32, 43, 43, 32, 32, 32, 32, 32, 32, 43, 43),
            preset("Pop", 32, 28, 39, 43, 44, 40, 29, 27, 27, 28, 28),
            preset("Reggae", 32, 32, 32, 30, 21, 32, 42, 42, 32, 32, 32),
            preset("Rock", 32, 44, 39, 22, 18, 25, 38, 46, 49, 49, 49),
            preset("Soft", 32, 39, 34, 29, 27, 29, 38, 45, 47, 49, 51),
            preset("Ska", 32, 27, 23, 24, 30, 38, 41, 46, 47, 49, 47),
            preset("Full Bass", 32, 47, 47, 47, 41, 34, 24, 17, 14, 13, 13),
            preset("Soft Rock", 32, 38, 38, 35, 30, 24, 22, 25, 30, 36, 46),
            preset("Full Treble", 32, 15, 15, 15, 24, 36, 49, 57, 57, 57, 59),
            preset("Full Bass & Treble", 32, 43, 41, 32, 19, 23, 34, 45, 49, 51, 51),
            preset("Live", 32, 23, 32, 38, 40, 41, 41, 38, 36, 36, 35),
            preset("Techno", 32, 44, 41, 32, 22, 23, 32, 44, 47, 47, 46),
        )

    /** What Load > Default applies when the user never saved their own default. */
    val FLAT = EqPreset("Default", EqOps.CENTER, List(BAND_COUNT) { EqOps.CENTER })

    private fun preset(
        name: String,
        preamp: Int,
        vararg bands: Int,
    ): EqPreset {
        require(bands.size == BAND_COUNT) { "$name has ${bands.size} bands" }
        return EqPreset(name, preamp, bands.toList())
    }

    private const val BAND_COUNT = 10
}
