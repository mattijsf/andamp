// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlin.math.abs
import kotlin.math.sin

/**
 * Analyzer signal for backends without an audio tap, fed through the shared
 * [SpectrumPhysics] like the real one.
 *
 * It is a drum pattern ([FakeBeat]) over a resting bed, so the bands do not all move
 * together:
 *
 * - the bed slopes from about 8px in the lowest bands to about 2px in the highest;
 * - a kick is strongest in the lowest bands;
 * - a snare has two humps, a body in the low-mid bands and a crack higher up;
 * - a hat lifts only the upper bands.
 *
 * Levels are in the display's pixels, and the sources combine by taking the loudest.
 *
 * The wobble on top is a smooth field across the bands, not per-band noise. It is largest
 * in the lowest bands and smallest in the highest.
 */
object FakeSpectrum {
    private const val BANDS = SpectrumPhysics.BANDS
    private const val MAX = SpectrumPhysics.MAX
    private val targets = FloatArray(BANDS)

    fun step(
        state: WinampState,
        timeSec: Double,
    ) {
        // the hits are the same for every band, so they are read once per frame
        val kick = FakeBeat.kick(timeSec)
        val snare = FakeBeat.snare(timeSec)
        val hat = FakeBeat.hat(timeSec)
        val strum = FakeBeat.strum(timeSec)
        // one note per sixteenth, each centered on a different band, so the bars do not move
        // as one block
        val note = NOTES[FakeBeat.sixteenth(timeSec).mod(NOTES.size)]
        for (band in 0 until BANDS) {
            val loudest =
                maxOf(
                    bed(band, timeSec),
                    KICK[band] * kick,
                    (SNARE[band] - SNARE_UNDER) * snare,
                    (HAT[band] - HAT_UNDER) * hat,
                    (STRUM[band] - AWAY * abs(band - note)) * strum,
                )
            targets[band] = (loudest + wobble(band, timeSec)).toFloat().coerceIn(0f, MAX)
        }
        SpectrumPhysics.step(state, targets)
    }

    fun reset(state: WinampState) {
        state.visLevels.fill(0f)
        state.visPeaks.fill(0f)
        state.visPeakHold.fill(0)
        state.visPeakVel.fill(0f)
    }

    /** What sounds between the hits. It drifts slowly instead of holding still. */
    private fun bed(
        band: Int,
        t: Double,
    ): Double = BED[band] + BED_DRIFT * sin(t * DRIFT_RATE + band * DRIFT_SPREAD)

    /** A smooth field across the bands, largest in the lowest bands. */
    private fun wobble(
        band: Int,
        t: Double,
    ): Double {
        val broad = sin(t * BROAD_RATE + band * BROAD_SPREAD)
        val fine = sin(t * FINE_RATE + band * FINE_SPREAD + FINE_PHASE)
        val size = WOBBLE_LOW - band * (WOBBLE_LOW - WOBBLE_HIGH) / (BANDS - 1)
        return (BROAD_SHARE * broad + (1 - BROAD_SHARE) * fine) * size
    }

    /** The resting level per band, in pixels. */
    private val BED =
        doubleArrayOf(
            8.0,
            8.5,
            8.2,
            7.9,
            7.6,
            7.3,
            7.0,
            6.6,
            6.3,
            6.0,
            5.6,
            5.2,
            4.8,
            4.4,
            4.0,
            3.5,
            3.0,
            2.6,
            2.2,
        )

    /** A kick's level per band, in pixels. */
    private val KICK =
        doubleArrayOf(
            16.0,
            15.7,
            14.7,
            13.3,
            12.0,
            11.0,
            10.0,
            9.3,
            9.0,
            9.0,
            9.3,
            9.7,
            10.0,
            10.3,
            9.7,
            8.3,
            6.7,
            4.7,
            2.7,
        )

    /** A snare's level per band, in pixels. */
    private val SNARE =
        doubleArrayOf(
            8.0,
            10.0,
            12.0,
            14.0,
            15.7,
            16.0,
            15.0,
            14.0,
            13.3,
            13.3,
            13.7,
            14.0,
            14.7,
            15.3,
            15.3,
            14.7,
            14.0,
            13.0,
            11.7,
        )

    /** A struck note's level per band, in pixels, before [AWAY] narrows it around its own band. */
    private val STRUM =
        doubleArrayOf(
            5.0,
            6.0,
            7.0,
            9.0,
            11.0,
            13.0,
            14.0,
            14.5,
            15.0,
            15.0,
            14.5,
            14.0,
            13.5,
            13.0,
            12.0,
            11.0,
            10.0,
            9.0,
            8.0,
        )

    /** The band each sixteenth's note is centered on. */
    private val NOTES = intArrayOf(8, 11, 6, 9, 13, 7, 10, 5, 12, 8, 4, 9, 14, 6, 11, 7)

    /** How fast a note falls away from its own band, in pixels per band. */
    private const val AWAY = 1.4

    /** A hat's level per band, in pixels. */
    private val HAT =
        doubleArrayOf(
            0.4,
            0.4,
            0.6,
            0.9,
            1.3,
            2.0,
            6.0,
            7.3,
            8.3,
            9.3,
            10.7,
            12.0,
            13.0,
            14.0,
            15.0,
            15.7,
            16.0,
            15.7,
            15.0,
        )

    /** Pixels subtracted from the snare's and the hat's levels. */
    private const val SNARE_UNDER = 1.0

    private const val HAT_UNDER = 4.0

    private const val BED_DRIFT = 1.1
    private const val DRIFT_RATE = 0.9
    private const val DRIFT_SPREAD = 0.7

    /** The wobble's size in pixels in the lowest and in the highest band. */
    private const val WOBBLE_LOW = 1.9
    private const val WOBBLE_HIGH = 0.4

    private const val BROAD_RATE = 2.3
    private const val BROAD_SPREAD = 0.8
    private const val BROAD_SHARE = 0.65
    private const val FINE_RATE = 5.7
    private const val FINE_SPREAD = 1.5
    private const val FINE_PHASE = 1.3
}
