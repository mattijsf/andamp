// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * Equalizer state a backend can apply. Winamp's ten bands (60Hz..16kHz),
 * each -12..+12dB, plus a preamp. Backends without [Capabilities.hasEqualizer]
 * ignore this.
 */
data class EqSettings(
    val enabled: Boolean,
    val preampDb: Float,
    val bandsDb: List<Float>,
) {
    companion object {
        val BAND_FREQUENCIES_HZ = listOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
        val FLAT = EqSettings(enabled = true, preampDb = 0f, bandsDb = List(10) { 0f })
    }
}
