// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlin.math.sin

/**
 * Oscilloscope signal for backends without an audio tap.
 *
 * It is shaped to resemble a trace of music: a bass-note carrier with its first harmonics,
 * broadband hash on top, and a phase that jumps about a third of a cycle per frame.
 *
 * The loudness follows [FakeBeat], so the trace swells on the beat like the analyzer bars.
 */
object FakeWave {
    fun step(
        state: WinampState,
        timeSec: Double,
    ) {
        val frame = (timeSec * FRAMES).toLong()
        // the phase moves about a third of a cycle per frame, so the trace does not crawl
        val phase = frame * JUMP
        val loud = QUIET + PUNCH * maxOf(FakeBeat.kick(timeSec), FakeBeat.snare(timeSec) * BACKBEAT)
        for (j in 0 until WinampState.WAVE_COLUMNS) {
            val across = j.toDouble() / WinampState.WAVE_COLUMNS
            val carrier =
                sin(TAU * (CYCLES * across + phase)) +
                    SECOND * sin(TAU * (2 * CYCLES * across + phase * SECOND_DRIFT)) +
                    THIRD * sin(TAU * (3 * CYCLES * across + phase * THIRD_DRIFT))
            val sample = loud * (carrier * TONE + hash(j, frame) * HASH)
            state.visWave[j] = RealOscilloscope.waveRow(sample.toFloat())
        }
    }

    /** Deterministic hash noise: the same column of the same frame is always the same. */
    private fun hash(
        column: Int,
        frame: Long,
    ): Double {
        var x = column * 73_856_093L xor (frame * 19_349_663L)
        x = x * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        return ((x ushr 12).toDouble() / (1L shl 51).toDouble()) - 1.0
    }

    private const val TAU = 2 * Math.PI

    /** Frames per second, used to turn the clock into frame steps. */
    private const val FRAMES = 60.0

    /** Cycles of the carrier across the window. */
    private const val CYCLES = 1.7

    /** Cycles the phase moves per frame. */
    private const val JUMP = 0.317

    private const val SECOND = 0.5
    private const val SECOND_DRIFT = 1.7
    private const val THIRD = 0.3
    private const val THIRD_DRIFT = 2.3

    /** Weights of the carrier and of the hash. */
    private const val TONE = 0.36
    private const val HASH = 0.28

    private const val QUIET = 0.55
    private const val PUNCH = 0.6

    /** The snare moves the trace less than the kick does. */
    private const val BACKBEAT = 0.7
}
