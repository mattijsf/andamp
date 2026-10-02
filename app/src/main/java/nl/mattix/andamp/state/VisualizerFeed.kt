// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap

/**
 * Which signal drives the analyzer and the oscilloscope this frame: the backend's audio
 * when there is any, the decorative one when there is not.
 *
 * The tap is asked for on every frame until there is one, because `audioTap` is null until
 * a backend has audio to offer, and a backend that reaches its engine over a binding
 * provides one after playback has started.
 */
class VisualizerFeed(
    private val frameMs: Long,
    private val tapOf: () -> AudioTap?,
) {
    private var spectrum: RealSpectrum? = null
    private var oscilloscope: RealOscilloscope? = null

    /** True once the backend's own audio is what is being drawn. */
    val onRealAudio: Boolean get() = spectrum != null

    fun stepAnalyzer(
        state: WinampState,
        timeSec: Double,
    ) {
        adopt()
        spectrum?.step(state) ?: FakeSpectrum.step(state, timeSec)
    }

    fun stepOscilloscope(
        state: WinampState,
        timeSec: Double,
    ) {
        adopt()
        oscilloscope?.step(state) ?: FakeWave.step(state, timeSec)
    }

    /**
     * Takes the tap on the first frame it exists and keeps it: both readers hold their own
     * smoothing over it, which rebuilding would reset.
     */
    private fun adopt() {
        if (spectrum != null) return
        val tap = tapOf() ?: return
        spectrum = RealSpectrum(tap, frameMs)
        oscilloscope = RealOscilloscope(tap, frameMs)
    }
}
