// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap

/**
 * Drives the analyzer from real audio: pulls the latest PCM window from the backend's
 * [AudioTap] each frame, FFTs it and maps the magnitudes onto the classic 19 bands. Motion
 * comes from [SpectrumPhysics], as for the fake signal.
 */
class RealSpectrum(
    tap: AudioTap,
    frameMs: Long,
) {
    private val columns = SpectrumColumns(tap, frameMs, POINTS)
    private val targets = FloatArray(SpectrumPhysics.BANDS)

    fun step(state: WinampState) {
        if (!columns.read()) {
            targets.fill(0f)
            SpectrumPhysics.step(state, targets)
            return
        }
        val points = columns.values
        for (band in 0 until SpectrumPhysics.BANDS) {
            var sum = 0f
            for (k in 0 until COLUMNS_PER_BAR) sum += points[band * COLUMNS_PER_BAR + k]
            targets[band] = (sum / COLUMNS_PER_BAR).coerceIn(0f, SpectrumPhysics.MAX)
        }
        SpectrumPhysics.step(state, targets)
    }

    private companion object {
        /** Columns averaged into one bar. */
        const val COLUMNS_PER_BAR = 4
        const val POINTS = SpectrumPhysics.BANDS * COLUMNS_PER_BAR
    }
}
