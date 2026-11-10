// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.state.SpectrumColumns
import nl.mattix.andamp.state.SpectrumPhysics

/**
 * One captured moment of the audio, as the skin's visualizer reads it: the spectrum levels and the
 * waveform the engine hands a skin, copied out while the audio is being followed and given to the
 * skin again when the picture is drawn.
 */
class VisFrame(
    /** [BANDS] levels 0..1. */
    val spectrum: FloatArray,
    /** [WAVE] samples -1..1. */
    val waveform: FloatArray,
) {
    /**
     * Whether the frame shows anything: a band above `QUIET`, or a waveform that is not a flat line.
     */
    val hasSignal: Boolean
        get() = spectrum.any { it > QUIET } || waveform.any { it != waveform.firstOrNull() }

    companion object {
        /** The bands of the spectrum a skin reads, as Winamp's visualizer has them. */
        const val BANDS = 75

        /** The samples of the waveform a skin reads, as Winamp hands a visualizer. */
        const val WAVE = 576

        /** The level below which a band counts as silent. */
        private const val QUIET = 0.05f

        /** The frame [columns] read last. */
        fun of(columns: SpectrumColumns): VisFrame =
            VisFrame(
                spectrum = FloatArray(columns.values.size) { (columns.values[it] / SpectrumPhysics.MAX).coerceIn(0f, 1f) },
                waveform = columns.wave.copyOf(),
            )
    }
}
