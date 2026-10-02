// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlin.math.exp

/**
 * The drum pattern behind the decorative analyzer.
 *
 * The clock is read as a bar of four-on-the-floor, and the bands are excited by what lands
 * on it. A plain clock would move every band together.
 *
 * 125 BPM lands on the frame clock: a beat is 480ms, thirty frames at sixty a second, and a
 * bar is 1920ms.
 *
 * The envelopes are short ([TAU]): the signal supplies the jump and the display's own fall
 * ([SpectrumPhysics]) supplies the decay.
 */
object FakeBeat {
    private val KICKS = doubleArrayOf(0.0, BEAT, 2 * BEAT, 3 * BEAT)

    private val SNARES = doubleArrayOf(BEAT, 3 * BEAT)

    private val HATS = doubleArrayOf(HALF, BEAT + HALF, 2 * BEAT + HALF, 3 * BEAT + HALF)

    private val SIXTEENTHS = DoubleArray(16) { it * (4 * BEAT) / 16 }

    private const val BEAT = 60.0 / 125.0

    private const val HALF = BEAT / 2

    /** Seconds a bar of four takes. */
    const val BAR = 4 * BEAT

    /** The kick, on every beat. */
    fun kick(t: Double) = loudest(t, KICKS)

    /** The snare, on two and four. */
    fun snare(t: Double) = loudest(t, SNARES)

    /** The closed hat, on the offbeat eighths. */
    fun hat(t: Double) = loudest(t, HATS)

    /** Everything else being struck (guitars, a bass note, a voice), on every sixteenth. */
    fun strum(t: Double) = loudest(t, SIXTEENTHS)

    /** Which sixteenth of which bar is sounding, so a note can differ from the one before. */
    fun sixteenth(t: Double): Int = (t / (BAR / SIXTEENTHS.size)).toInt()

    /** How hard this hit is landing now, 0..1, with a slight variation in loudness per bar. */
    private fun loudest(
        t: Double,
        beats: DoubleArray,
    ): Double {
        val inBar = t.mod(BAR)
        var most = 0.0
        for (beat in beats) {
            val since = (inBar - beat).mod(BAR)
            if (since > REACH) continue
            most = maxOf(most, exp(-since / TAU) * velocity(t - since, beat))
        }
        return most
    }

    /** Within about a decibel of full, decided by the bar and the beat. */
    private fun velocity(
        t: Double,
        beat: Double,
    ): Double {
        val bar = (t / BAR).toInt()
        return 1.0 - SWING * ((bar * 7 + (beat * 10).toInt() * 3) % 5) / 5.0
    }

    /** The decay time constant of a hit, in seconds. */
    private const val TAU = 0.04

    /** Seconds after which a hit contributes nothing. */
    private const val REACH = 0.25

    private const val SWING = 0.12
}
