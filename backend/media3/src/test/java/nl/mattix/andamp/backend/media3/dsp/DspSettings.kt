// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

/**
 * The settings of the hand-written reference stages in the test sources, one
 * type per stage.
 *
 * Every parameter is 0..1. What the ends mean is documented on the stage that
 * reads it.
 */
internal object DspSettings {
    /**
     * Vocal removal: what is common to both channels is what sits in the
     * middle of the mix, so subtracting one channel from the other takes the
     * singer with it — and the bass and kick, which is what the filter is for.
     *
     * [level] how much of the cancelled signal is used, [filter] where the
     * bass that gets kept is rolled off, [band] how wide that kept band is,
     * [width] how much of the original stereo edges are mixed back in.
     */
    data class Karaoke(
        val enabled: Boolean = false,
        val level: Float = 1f,
        val filter: Float = 0.35f,
        val band: Float = 0.5f,
        val width: Float = 0.3f,
    )

    /** A pan independent of the main window's balance. 0 left, 0.5 center, 1 right. */
    data class Pan(
        val enabled: Boolean = false,
        val pan: Float = 0.5f,
    )

    /**
     * Chorus, flanger and phaser: an LFO sweeps a delay line, or a chain of
     * all-pass sections for the phaser. [mode] picks which.
     *
     * [level] wet/dry mix, [lfo] the sweep's shape (0 sine, 1 triangle),
     * [depth] how far the delay sweeps, [rate] how fast, [feedback] how much
     * output returns to the input, [stereo] how far the channels' sweeps are
     * pushed apart in phase.
     */
    data class Modulation(
        val enabled: Boolean = false,
        val mode: Mode = Mode.FLANGER,
        val level: Float = 0.5f,
        val lfo: Float = 0f,
        val depth: Float = 0.5f,
        val rate: Float = 0.25f,
        val feedback: Float = 0.4f,
        val stereo: Float = 0.5f,
    )

    enum class Mode {
        CHORUS,
        FLANGER,
        PHASER,
    }

    /**
     * A room around the music. [level] wet/dry mix, [size] how big the room
     * is, [near] how present the early reflections are, [air] how much high
     * frequency survives each bounce.
     */
    data class Reverb(
        val enabled: Boolean = false,
        val level: Float = 0.25f,
        val size: Float = 0.5f,
        val near: Float = 0.5f,
        val air: Float = 0.5f,
    )
}
