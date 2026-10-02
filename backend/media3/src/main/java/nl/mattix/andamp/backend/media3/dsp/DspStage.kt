// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

/**
 * One effect in the DSP rack.
 *
 * Stages run one after another on the same frame, in place: a frame is the
 * samples of every channel at one instant, in -1..1. Working per frame lets a
 * stereo effect see both channels at once.
 *
 * Implementations are pure arithmetic with no Android or Media3 types, so an
 * effect is tested by feeding it samples.
 */
internal interface DspStage {
    /**
     * Processes [frame] in place. [frame] holds one sample per channel; a
     * stage that needs stereo and is handed one channel leaves it alone.
     */
    fun process(frame: FloatArray)

    /** Drops any tail, so a seek or a track change does not carry it over. */
    fun reset()
}
