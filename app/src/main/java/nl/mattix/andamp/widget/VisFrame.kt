// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.state.WinampState

/**
 * One captured moment of the visualizer, whichever kind it is showing.
 *
 * The widget draws through the player's own visualizer code, which reads its signal from a
 * [WinampState]. A frame is that state's scratch arrays, copied out while the audio is being
 * followed and written back when the picture is drawn. All three arrays are copied whatever the
 * mode, so the peak caps stay with the levels they belong to.
 */
class VisFrame private constructor(
    private val levels: FloatArray,
    private val peaks: FloatArray,
    private val wave: IntArray,
) {
    /**
     * Whether the frame shows anything: a bar above `QUIET`, or a waveform that is not a flat line.
     */
    val hasSignal: Boolean
        get() = levels.any { it > QUIET } || wave.any { it != wave.firstOrNull() }

    fun applyTo(state: WinampState) {
        levels.copyInto(state.visLevels)
        peaks.copyInto(state.visPeaks)
        wave.copyInto(state.visWave)
    }

    companion object {
        /** The level below which a bar counts as silent. */
        private const val QUIET = 0.05f

        /**
         * [dropCaps] leaves the peak caps out of the frame. A cap holds and then falls, and the
         * widget's loop replays about 0.4 s, so a cap in it never completes its fall and shows as a
         * stripe frozen above its bar.
         */
        fun of(
            state: WinampState,
            dropCaps: Boolean = false,
        ): VisFrame =
            VisFrame(
                levels = state.visLevels.copyOf(),
                peaks = if (dropCaps) FloatArray(state.visPeaks.size) else state.visPeaks.copyOf(),
                wave = state.visWave.copyOf(),
            )
    }
}
