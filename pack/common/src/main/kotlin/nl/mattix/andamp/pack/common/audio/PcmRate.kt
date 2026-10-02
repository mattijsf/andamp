// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import nl.mattix.andamp.core.playback.PcmProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A stereo stream arriving at one sample rate, leaving at another.
 *
 * [PcmProvider] fixes 44.1 kHz, and a library also holds other rates, 48 kHz above all.
 * Passed on unconverted, a 48 kHz file would play about nine percent fast.
 *
 * The conversion is a band-limited windowed-sinc filter; see [PcmSinc]. `PcmRateFilterTest`
 * holds an alias or an image at least 60 dB down and a passband tone within 0.1 dB.
 *
 * The position is an integer numerator over [to], so the step is `from/to` with no
 * rounding drift. The same numerator selects the filter phase.
 *
 * The output lags the input by [PcmSinc.reach] input frames: 53 frames (1.1 ms) from
 * 48 kHz, 48 frames (2.2 ms) from 22.05 kHz. A symmetric filter needs as many frames after
 * its center as before, and a stream cannot read ahead; the fixed lag keeps the count of
 * frames out for frames in exact. The frames before the first are taken to be silence.
 *
 * One instance belongs to one stretch of decoding. It carries the last [taps] frames of
 * each block into the next, so the output does not depend on how the stream is sliced into
 * blocks, and [reset] must be called at a seek.
 *
 * Nothing allocates after construction.
 */
internal class PcmRate(
    private val from: Int,
    private val to: Int = PcmProvider.SAMPLE_RATE_HZ,
) {
    /** Null at a ratio of one, where the input is copied. */
    private val sinc: PcmSinc? = if (from == to) null else PcmSinc(from, to)

    /** How many input frames one output frame is filtered from. */
    private val taps = sinc?.taps ?: 0

    /** The last [taps] frames of input, oldest first, for the start of the next block to reach back into. */
    private val history = ShortArray(taps * PcmProvider.CHANNELS)

    /** One output frame's coefficients, blended here when the table's phases are interpolated. */
    private val blend = DoubleArray(taps)

    /** Where the next output sample sits, as a numerator over [to], relative to this block's first frame. */
    private var cursor = 0L

    /** Back to the start of a stream; called after a seek. */
    fun reset() {
        cursor = 0L
        history.fill(0)
    }

    /**
     * An upper bound on what [convert] writes for [samples] input samples, so the caller
     * can size its array once. It is loose by a frame or two, because the exact count
     * depends on where the cursor is.
     */
    fun room(samples: Int): Int {
        val frames = samples / PcmProvider.CHANNELS
        return (((frames + 1).toLong() * to / from).toInt() + SLACK_FRAMES) * PcmProvider.CHANNELS
    }

    /**
     * Reads [samples] interleaved stereo samples from [source] and writes the
     * same music at [to] into [into], answering how many samples that came to.
     */
    fun convert(
        source: ShortArray,
        samples: Int,
        into: ShortArray,
    ): Int {
        val frames = samples / PcmProvider.CHANNELS
        val filter = sinc
        if (frames == 0) return 0
        if (filter == null) {
            System.arraycopy(source, 0, into, 0, frames * PcmProvider.CHANNELS)
            return frames * PcmProvider.CHANNELS
        }
        val span = to.toLong()
        val last = (frames - 1).toLong() * span
        var at = 0
        while (cursor <= last) {
            // the newest frame this output reads is the one the cursor is in; the filter's
            // center sits reach frames before it
            val newest = cursor.floorDiv(span).toInt()
            val offset = filter.row(cursor.mod(span), blend)
            val coefficients = if (offset < 0) blend else filter.table
            val row = offset.coerceAtLeast(0)
            for (channel in 0 until PcmProvider.CHANNELS) {
                into[at++] = dot(source, newest - taps + 1, channel, coefficients, row)
            }
            cursor += from
        }
        cursor -= frames.toLong() * span
        remember(source, frames)
        return at
    }

    /**
     * One channel of one output sample: [taps] input frames starting at [first], weighted
     * by the coefficients at [row] of [coefficients]. A negative frame index is in the
     * previous block and is read from [history].
     */
    private fun dot(
        source: ShortArray,
        first: Int,
        channel: Int,
        coefficients: DoubleArray,
        row: Int,
    ): Short {
        val carried = (-first).coerceIn(0, taps)
        var sum = 0.0
        val back = (taps + first) * PcmProvider.CHANNELS + channel
        for (tap in 0 until carried) {
            sum += coefficients[row + tap] * history[back + tap * PcmProvider.CHANNELS]
        }
        val ahead = first * PcmProvider.CHANNELS + channel
        for (tap in carried until taps) {
            sum += coefficients[row + tap] * source[ahead + tap * PcmProvider.CHANNELS]
        }
        return sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** The newest [taps] frames of what has been read so far, history and this block together. */
    private fun remember(
        source: ShortArray,
        frames: Int,
    ) {
        val lane = PcmProvider.CHANNELS
        if (frames >= taps) {
            System.arraycopy(source, (frames - taps) * lane, history, 0, taps * lane)
        } else {
            System.arraycopy(history, frames * lane, history, 0, (taps - frames) * lane)
            System.arraycopy(source, 0, history, (taps - frames) * lane, frames * lane)
        }
    }

    private companion object {
        /** Room for the frame the cursor may already be part way into, and one to round on. */
        const val SLACK_FRAMES = 2
    }
}

/**
 * The filter [PcmRate] reads, tabulated once for one pair of rates.
 *
 * It is a sinc lowpass at the lower of the two Nyquist frequencies, which removes aliases
 * when the rate comes down and images when it goes up.
 *
 * The window is a Kaiser window with beta [BETA] and [CROSSINGS] zero crossings each side
 * of the center at the lower rate; the cutoff is [ROLLOFF] of the lower Nyquist. From
 * 48 kHz that is 106 taps per output frame.
 *
 * Phases. The output falls between input samples at `(n * from mod to) / to`, and those
 * fractions repeat with a period of `to / gcd(from, to)`: 147 for 48 to 44.1 kHz, 2 for
 * 22.05 to 44.1 kHz. Up to [MAX_PHASES] the table has one exact row per fraction. Above
 * that (47999 Hz would need 6300) the table holds [MAX_PHASES] evenly spaced rows, and an
 * output between two of them blends them linearly.
 *
 * Every row is normalized to sum to one, so a constant input gives the same constant
 * output at every phase.
 */
private class PcmSinc(
    from: Int,
    to: Int,
) {
    /** How many input frames the center of the filter sits behind the newest frame it reads. */
    val reach: Int

    /** Input frames read per output frame: twice [reach], the kernel's full span. */
    val taps: Int

    /** Rows of [taps] coefficients, oldest frame first; one row past the last when rows are blended. */
    val table: DoubleArray

    private val span = to.toLong()
    private val phases: Int
    private val blended: Boolean

    init {
        val lower = minOf(from, to)
        // one unit of the lower rate is this many input frames
        val stretch = from.toDouble() / lower
        reach = ceil(CROSSINGS * stretch).toInt()
        taps = reach * 2
        val exact = to / gcd(from, to)
        blended = exact > MAX_PHASES
        phases = if (blended) MAX_PHASES else exact
        val rows = if (blended) phases + 1 else phases
        table = DoubleArray(rows * taps)
        for (row in 0 until rows) fill(row, stretch)
    }

    /**
     * Where the coefficients for the output at [fraction] (a numerator over
     * `to`) begin in [table] - or, when the rows are blended, -1 with the
     * blend written into [blend].
     */
    fun row(
        fraction: Long,
        blend: DoubleArray,
    ): Int {
        val scaled = fraction * phases
        val row = (scaled / span).toInt()
        if (!blended) return row * taps
        val weight = (scaled % span).toDouble() / span
        val low = row * taps
        val high = low + taps
        for (tap in 0 until taps) blend[tap] = table[low + tap] + (table[high + tap] - table[low + tap]) * weight
        return -1
    }

    /**
     * The row for a fraction of [row] / [phases] past the newest frame's start, normalized
     * to sum to one. Tap `k` reads the frame `taps - 1 - k` frames before the newest, and
     * sits that many frames plus the fraction, less [reach], from the filter's center.
     */
    private fun fill(
        row: Int,
        stretch: Double,
    ) {
        val fraction = row.toDouble() / phases
        val start = row * taps
        var sum = 0.0
        for (tap in 0 until taps) {
            val value = kernel((fraction + reach - 1 - tap) / stretch)
            table[start + tap] = value
            sum += value
        }
        for (tap in 0 until taps) table[start + tap] /= sum
    }

    private companion object {
        const val BETA = 8.0
        const val CROSSINGS = 48
        const val ROLLOFF = 0.94

        /** The most rows tabulated exactly; past it, this many rows blended. */
        const val MAX_PHASES = 512

        /** Terms of the Bessel series below which a term no longer moves the sum. */
        const val BESSEL_EPSILON = 1e-12

        /** The windowed sinc at [time] samples of the lower rate from its center; zero outside the window. */
        fun kernel(time: Double): Double {
            val position = time / CROSSINGS
            if (abs(position) >= 1.0) return 0.0
            val x = PI * ROLLOFF * time
            val sinc = if (x == 0.0) 1.0 else sin(x) / x
            return sinc * bessel(BETA * sqrt(1.0 - position * position))
        }

        /** The zeroth-order modified Bessel function, by its power series; the window's shape. */
        fun bessel(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            val half = x / 2
            while (term > BESSEL_EPSILON * sum) {
                term *= (half / k) * (half / k)
                sum += term
                k++
            }
            return sum
        }

        tailrec fun gcd(
            a: Int,
            b: Int,
        ): Int = if (b == 0) a else gcd(b, a % b)
    }
}
