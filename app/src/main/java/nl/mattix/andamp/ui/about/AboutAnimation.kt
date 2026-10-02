// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.about

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The warp of Winamp's about box: every few seconds the picture pulls itself into a tunnel, rings,
 * and snaps back.
 *
 * Each frame is the previous frame resampled about a moving center, nearest-neighbor with integer
 * truncation. The truncation produces the comb of horizontal stripes, so nothing here interpolates.
 * The still image is blended back in faintly every frame, which keeps the logo readable through the
 * smear.
 *
 * Arithmetic over int arrays, with no Android and no clock of its own: the caller says how much
 * time passed.
 */
class AboutAnimation(
    private val width: Int,
    private val height: Int,
    /**
     * The still pictures, ARGB, `width * height` each. With more than one, each burst hands over to
     * the next picture at its peak.
     */
    private val pictures: List<IntArray>,
) {
    constructor(width: Int, height: Int, source: IntArray) : this(width, height, listOf(source))

    init {
        require(width > 0 && height > 0) { "an animation needs a surface: ${width}x$height" }
        require(pictures.isNotEmpty()) { "an animation needs something to show" }
        pictures.forEach {
            require(it.size == width * height) { "a picture is ${it.size}, expected ${width * height}" }
        }
    }

    /** What to show now. Valid after the first [advance]; starts as the first picture. */
    val pixels: IntArray = pictures.first().copyOf()

    private var previous: IntArray = pictures.first().copyOf()
    private var elapsed = 0L

    /** Where in the cycle this is, in milliseconds. Exposed for tests. */
    val cycleMillis: Long get() = elapsed % CYCLE

    /**
     * The picture being fed in right now, worked out from the clock: how many bursts have gone by
     * and whether this one is past its peak.
     */
    private val source: IntArray
        get() {
            val turns = elapsed / CYCLE
            val handedOver = if (cycleMillis >= REST + BURST / 2) 1 else 0
            return pictures[((turns + handedOver) % pictures.size).toInt()]
        }

    /** Advances by [millis] and rewrites [pixels]. */
    fun advance(millis: Long) {
        require(millis >= 0) { "time does not run backwards: $millis" }
        elapsed += millis
        val at = cycleMillis
        when {
            at < REST -> rest()
            at < REST + BURST -> warp(phase = (at - REST).toFloat() / BURST, settling = false)
            else -> warp(phase = (at - REST - BURST).toFloat() / SETTLE, settling = true)
        }
        // the frame just drawn is what the next one samples
        val spent = previous
        previous = pixels.copyInto(spent)
    }

    /**
     * Starts a burst now instead of at the end of this rest, as Winamp's about box answered a
     * click. A poke during a burst does nothing.
     */
    fun poke() {
        if (cycleMillis >= REST) return
        elapsed += REST - cycleMillis
    }

    /** Between bursts the picture is shown as it is. */
    private fun rest() {
        source.copyInto(pixels)
    }

    /**
     * One feedback pass. [phase] runs 0..1 through whichever part of the cycle this is. Settling
     * runs the same pass with the warp switched off and the still picture arriving quickly.
     */
    private fun warp(
        phase: Float,
        settling: Boolean,
    ) {
        // read once: [source] is computed from the clock, and the loop below reads it twice per
        // pixel
        val picture = source
        // a burst grows and falls away inside its own span, so it neither
        // starts nor ends on an edge
        val strength = if (settling) 0f else sin(PI * phase).pow(SHOULDER).toFloat()
        // the zoom reverses as well as magnifying: pulling back folds the smear onto itself, which
        // makes the rings. BIAS keeps a little more push than pull, so a burst travels outwards on
        // balance
        val swing = sin(TWO_PI * phase * SWINGS).toFloat()
        val scale = 1f + strength * ZOOM * (BIAS + (1f - BIAS) * swing)
        val inverse = 1f / scale
        val centreX = width / 2f + width * DRIFT * sin(TWO_PI * phase * DRIFT_TURNS).toFloat()
        val centreY = height / 2f + height * DRIFT * sin(TWO_PI * phase * DRIFT_TURNS + QUARTER).toFloat()
        val ripple = strength * width * RIPPLE
        val alpha = if (settling) (BLEND + (FULL - BLEND) * phase).toInt().coerceAtMost(FULL) else BLEND

        for (y in 0 until height) {
            // truncated, not rounded: this makes the comb
            val sourceY = (centreY + (y - centreY) * inverse).toInt()
            val shift = (ripple * sin(y * WAVE + TWO_PI * phase * WAVE_TURNS)).roundToInt()
            val row = y * width
            for (x in 0 until width) {
                val sourceX = (centreX + (x - centreX) * inverse).toInt() + shift
                // a source off the edge reads the still picture, not a stretched border pixel
                val fed =
                    if (sourceX in 0 until width && sourceY in 0 until height) {
                        previous[sourceY * width + sourceX]
                    } else {
                        picture[row + x]
                    }
                pixels[row + x] = blend(fed, picture[row + x], alpha)
            }
        }
    }

    /** [over] laid on [under] at [alpha] of 255, per channel, opaque throughout. */
    private fun blend(
        under: Int,
        over: Int,
        alpha: Int,
    ): Int {
        if (alpha >= FULL) return over
        val rest = FULL - alpha
        val r = ((under ushr RED and MASK) * rest + (over ushr RED and MASK) * alpha) / FULL
        val g = ((under ushr GREEN and MASK) * rest + (over ushr GREEN and MASK) * alpha) / FULL
        val b = ((under and MASK) * rest + (over and MASK) * alpha) / FULL
        return OPAQUE or (r shl RED) or (g shl GREEN) or b
    }

    private companion object {
        /** Still, warped, and coming back: the shape of one turn. */
        const val REST = 1_800L
        const val BURST = 2_600L
        const val SETTLE = 650L
        const val CYCLE = REST + BURST + SETTLE

        /** How far one frame magnifies the last at full strength. Small: it compounds every frame. */
        const val ZOOM = 0.085f

        /** How much of the swing is push; 0 would breathe in place. */
        const val BIAS = 0.2f

        /** Shapes the burst's envelope: below 1 it arrives sooner and lingers. */
        const val SHOULDER = 0.6

        /** How many times the zoom reverses inside a burst. Each reversal is a ring. */
        const val SWINGS = 2f

        /** How far the center wanders, as a share of the picture, and how often. */
        const val DRIFT = 0.07f
        const val DRIFT_TURNS = 0.75f

        /** The sideways wave: a pixel or two of displaced scanlines. */
        const val RIPPLE = 0.005f
        const val WAVE = 1.1f
        const val WAVE_TURNS = 3f

        /** How much of the still picture arrives each frame while warping, out of 255. */
        const val BLEND = 32

        const val FULL = 255
        const val MASK = 0xFF
        const val OPAQUE = 0xFF shl 24
        const val RED = 16
        const val GREEN = 8
        const val TWO_PI = 2.0 * PI
        const val QUARTER = PI / 2
    }
}
