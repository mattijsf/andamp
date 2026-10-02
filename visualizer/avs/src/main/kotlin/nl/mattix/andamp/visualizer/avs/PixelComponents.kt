// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import java.util.Random
import kotlin.math.abs

// Ten components that are each a loop over the frame's IntArray.
//
// Field layouts transcribed from grandchild/AVS-File-Decoder (MIT); arithmetic
// transcribed from the vis_avs source (BSD 3-clause, Nullsoft); see NOTICE.md.
// The shared field readers live in BodyFields.kt.

/** Doubles, halves, or leaves alone, as its one field says. */
internal class FastBrightnessRenderer(
    private val factor: Double,
) : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (factor == 1.0) return
        for (i in frame.pixels.indices) {
            val pixel = frame.pixels[i]
            frame.pixels[i] =
                pixelOf(
                    (redOf(pixel) * factor).toInt(),
                    (greenOf(pixel) * factor).toInt(),
                    (blueOf(pixel) * factor).toInt(),
                )
        }
    }

    companion object {
        private val FACTORS = listOf(2.0, 0.5, 1.0)

        fun read(body: ByteArray): FastBrightnessRenderer? {
            val reader = BodyReader(body)
            val factor = FACTORS.getOrNull(reader.int32()) ?: 1.0
            return if (reader.ok) FastBrightnessRenderer(factor) else null
        }
    }
}

/**
 * Per-channel gain.
 *
 * Transcribed from vis_avs `e_brightness.cpp` (BSD; see NOTICE.md). The gains
 * run -4096..4096 around unity, but the two halves of the dial are not
 * symmetric: the positive side is sixteen times as steep, so +4096 multiplies
 * by seventeen (clamped per channel), while -4096 takes a channel to nothing.
 * A pixel within the exclude distance of the excluded color on every channel
 * (an axis-aligned cube, not a summed distance) is left alone.
 */
internal class BrightnessRenderer(
    private val enabled: Boolean,
    red: Int,
    green: Int,
    blue: Int,
    private val blend: AvsBlendMode,
    private val exclude: Boolean = false,
    private val excludeColour: Int = 0,
    private val excludeDistance: Int = 0,
) : AvsComponentRenderer {
    private val redTable = tableFor(red)
    private val greenTable = tableFor(green)
    private val blueTable = tableFor(blue)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        for (i in frame.pixels.indices) {
            val pixel = frame.pixels[i]
            if (exclude && near(pixel)) continue
            val lit = pixelOf(redTable[redOf(pixel)], greenTable[greenOf(pixel)], blueTable[blueOf(pixel)])
            frame.pixels[i] = if (blend == AvsBlendMode.REPLACE) lit else AvsBlend.pixel(blend, lit, pixel)
        }
    }

    /** e_brightness.cpp's `in_range`: every channel independently within the distance. */
    private fun near(pixel: Int) =
        abs(redOf(pixel) - redOf(excludeColour)) <= excludeDistance &&
            abs(greenOf(pixel) - greenOf(excludeColour)) <= excludeDistance &&
            abs(blueOf(pixel) - blueOf(excludeColour)) <= excludeDistance

    companion object {
        private const val UNITY = 4096
        private const val POSITIVE_STEEPNESS = 16
        private const val FIXED_ONE = 65536.0
        private const val FULL = 255

        /**
         * `(1 + (v < 0 ? 1 : 16) * v/4096) * 65536`, then `(n * that) >> 16`
         * clamped 0..255: e_brightness.cpp's three `*_tab` loops, whose masks
         * and shifts all reduce to this per-channel value.
         */
        private fun tableFor(gain: Int): IntArray {
            val steepness = if (gain < 0) 1 else POSITIVE_STEEPNESS
            val scale = ((1 + steepness * (gain.toFloat() / UNITY)) * FIXED_ONE).toInt()
            return IntArray(FULL + 1) { n -> ((n * scale) shr 16).coerceIn(0, FULL) }
        }

        fun read(body: ByteArray): BrightnessRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            // e_brightness.cpp reads the additive int first, lets the 50/50
            // int override it, and treats any nonzero as set, so this is not
            // the shared pairBlend (whose low word 2 would mean "default")
            val additive = reader.int32() != 0
            val fifty = reader.int32() != 0
            val blend =
                when {
                    fifty -> AvsBlendMode.FIFTY_FIFTY
                    additive -> AvsBlendMode.ADDITIVE
                    else -> AvsBlendMode.REPLACE
                }
            val red = reader.int32()
            val green = reader.int32()
            val blue = reader.int32()
            // on_separate_toggle at load: linked sliders mean the red gain
            // rules all three, whatever the file stored for the other two
            val separate = reader.int32() != 0
            val excludeColour = AvsFrame.fromConfig(reader.int32())
            val exclude = reader.int32() != 0
            val distance = reader.int32()
            return if (reader.ok) {
                BrightnessRenderer(
                    enabled,
                    red,
                    if (separate) green else red,
                    if (separate) blue else red,
                    blend,
                    exclude,
                    excludeColour,
                    distance,
                )
            } else {
                null
            }
        }
    }
}

/**
 * Drifts a pixel's channels apart, brightest first.
 *
 * Transcribed from vis_avs `e_colorfade.cpp` (BSD; see NOTICE.md). The
 * strictly brightest channel takes the max fader and the other two follow
 * cyclically (red brightest sends the 2nd fader to blue, green brightest sends
 * it to red, blue brightest to green), and any tie for brightest sends all
 * three channels to the 3rd/gray fader. In on-beat mode a beat sets the
 * current faders and every following frame walks them back toward the normal
 * set by one; for files with version byte 0 the max and 3rd/gray faders chase
 * each other's targets, as in v2.81d, swapping those two slots between beats.
 */
internal class ColorfadeRenderer(
    private val enabled: Boolean,
    private val fader2nd: Int,
    private val faderMax: Int,
    private val fader3rdGray: Int,
    private val onBeat: Boolean = false,
    private val onBeatRandom: Boolean = false,
    private val swappedWalk: Boolean = true,
    private val beat2nd: Int = 0,
    private val beatMax: Int = 0,
    private val beat3rdGray: Int = 0,
    private val random: Random = Random(SEED),
) : AvsComponentRenderer {
    private var cur2nd = fader2nd
    private var curMax = faderMax
    private var cur3rdGray = fader3rdGray

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        step(state.beat)
        for (i in frame.pixels.indices) {
            frame.pixels[i] = fade(frame.pixels[i])
        }
    }

    /** The per-frame fader walk, e_colorfade.cpp's smp_begin. */
    private fun step(beat: Boolean) {
        cur2nd = cur2nd.stepToward(fader2nd)
        if (swappedWalk) {
            // v2.81d: the max fader approaches the 3rd/gray target and vice
            // versa
            curMax = curMax.stepToward(fader3rdGray)
            cur3rdGray = cur3rdGray.stepToward(faderMax)
        } else {
            curMax = curMax.stepToward(faderMax)
            cur3rdGray = cur3rdGray.stepToward(fader3rdGray)
        }
        when {
            !onBeat -> {
                cur2nd = fader2nd
                curMax = faderMax
                cur3rdGray = fader3rdGray
            }

            beat && onBeatRandom -> {
                cur2nd = random.nextInt(RANDOM_SMALL) - RANDOM_SMALL_BIAS
                curMax = random.nextInt(RANDOM_WIDE) - RANDOM_WIDE_BIAS
                if (curMax < 0 && curMax > -DEAD_ZONE) curMax = -RANDOM_WIDE_BIAS
                if (curMax >= 0 && curMax < DEAD_ZONE) curMax = RANDOM_WIDE_BIAS
                cur3rdGray = random.nextInt(RANDOM_SMALL) - RANDOM_SMALL_BIAS
            }

            beat -> {
                cur2nd = beat2nd
                curMax = beatMax
                cur3rdGray = beat3rdGray
            }
        }
    }

    private fun Int.stepToward(target: Int) =
        when {
            this < target -> this + 1
            this > target -> this - 1
            else -> this
        }

    private fun fade(pixel: Int): Int {
        val r = redOf(pixel)
        val g = greenOf(pixel)
        val b = blueOf(pixel)
        return when {
            g > r && g > b -> pixelOf(r + cur2nd, g + curMax, b + cur3rdGray)
            b > r && b > g -> pixelOf(r + cur3rdGray, g + cur2nd, b + curMax)
            r > g && r > b -> pixelOf(r + curMax, g + cur3rdGray, b + cur2nd)
            else -> pixelOf(r + cur3rdGray, g + cur3rdGray, b + cur3rdGray)
        }
    }

    companion object {
        private const val ENABLED_BIT = 0x01
        private const val RANDOM_BIT = 0x02
        private const val ON_BEAT_BIT = 0x04
        private const val VERSION_SHIFT = 24
        private const val VERSION_MASK = 0x7F
        private const val RANDOM_SMALL = 32
        private const val RANDOM_SMALL_BIAS = 6
        private const val RANDOM_WIDE = 64
        private const val RANDOM_WIDE_BIAS = 32
        private const val DEAD_ZONE = 16
        private const val SEED = 0x66616465L

        /**
         * A bitfield (enabled, random, on-beat, and a version in the high
         * byte), then the two fader sets stored as (2nd, max, 3rd/gray): the
         * second value of each set drives the brightest channel. A body that
         * ends before the beat set gets the normal set for it, as
         * e_colorfade.cpp's length-guarded reads do.
         */
        fun read(body: ByteArray): ColorfadeRenderer? {
            val reader = BodyReader(body)
            val flags = reader.int32()
            val fader2nd = reader.int32()
            val faderMax = reader.int32()
            val fader3rdGray = reader.int32()
            if (!reader.ok) return null
            val beat2nd = reader.int32().takeIf { reader.ok } ?: fader2nd
            val beatMax = reader.int32().takeIf { reader.ok } ?: faderMax
            val beat3rdGray = reader.int32().takeIf { reader.ok } ?: fader3rdGray
            return ColorfadeRenderer(
                enabled = flags and ENABLED_BIT != 0,
                fader2nd = fader2nd,
                faderMax = faderMax,
                fader3rdGray = fader3rdGray,
                onBeat = flags and ON_BEAT_BIT != 0,
                onBeatRandom = flags and RANDOM_BIT != 0,
                // version 0 is the Winamp-era file; vis_avs saves 1
                swappedWalk = (flags shr VERSION_SHIFT) and VERSION_MASK == 0,
                beat2nd = beat2nd,
                beatMax = beatMax,
                beat3rdGray = beat3rdGray,
            )
        }
    }
}

/** Which pixels a [ColorClipRenderer] replaces. */
internal enum class AvsClipMode { OFF, BELOW, ABOVE, NEAR }

/**
 * Replaces the pixels that are near a color, or past it.
 *
 * The distance is the one the decoder records: squared per channel, against
 * `(level * 2)` squared.
 */
internal class ColorClipRenderer(
    private val mode: AvsClipMode,
    private val against: Int,
    private val replacement: Int,
    private val level: Int,
) : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (mode == AvsClipMode.OFF) return
        for (i in frame.pixels.indices) {
            if (clips(frame.pixels[i])) frame.pixels[i] = replacement
        }
    }

    private fun clips(pixel: Int): Boolean {
        val r = redOf(pixel)
        val g = greenOf(pixel)
        val b = blueOf(pixel)
        return when (mode) {
            AvsClipMode.BELOW -> {
                r <= redOf(against) && g <= greenOf(against) && b <= blueOf(against)
            }

            AvsClipMode.ABOVE -> {
                r >= redOf(against) && g >= greenOf(against) && b >= blueOf(against)
            }

            AvsClipMode.NEAR -> {
                val dr = r - redOf(against)
                val dg = g - greenOf(against)
                val db = b - blueOf(against)
                dr * dr + dg * dg + db * db <= (level * 2) * (level * 2)
            }

            AvsClipMode.OFF -> {
                false
            }
        }
    }

    companion object {
        fun read(body: ByteArray): ColorClipRenderer? {
            val reader = BodyReader(body)
            // e_colorclip.cpp: 0 is off, 1 below, 2 above, and anything else
            // falls into the NEAR case with the effect enabled
            val mode =
                when (reader.int32()) {
                    0 -> AvsClipMode.OFF
                    1 -> AvsClipMode.BELOW
                    2 -> AvsClipMode.ABOVE
                    else -> AvsClipMode.NEAR
                }
            val against = AvsFrame.fromConfig(reader.int32())
            val replacement = AvsFrame.fromConfig(reader.int32())
            val level = reader.int32()
            return if (reader.ok) ColorClipRenderer(mode, against, replacement, level) else null
        }
    }
}

/**
 * Everything in one color, by how bright it is.
 *
 * A pixel's brightest channel picks a point along the chosen color, so the
 * picture keeps its shape and loses its hues. Transcribed from vis_avs
 * `e_uniquetone.cpp` (BSD; see NOTICE.md): the tables are built with float
 * arithmetic, `(i / 255.0) * channel` truncated, which can land one below
 * integer division.
 */
internal class UniqueToneRenderer(
    private val enabled: Boolean,
    tone: Int,
    private val invert: Boolean,
    private val blend: AvsBlendMode,
) : AvsComponentRenderer {
    private val redTable = tableFor(redOf(tone))
    private val greenTable = tableFor(greenOf(tone))
    private val blueTable = tableFor(blueOf(tone))

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        for (i in frame.pixels.indices) {
            val pixel = frame.pixels[i]
            val brightest = maxOf(redOf(pixel), greenOf(pixel), blueOf(pixel))
            val level = if (invert) FULL - brightest else brightest
            val toned = pixelOf(redTable[level], greenTable[level], blueTable[level])
            frame.pixels[i] = if (blend == AvsBlendMode.REPLACE) toned else AvsBlend.pixel(blend, toned, pixel)
        }
    }

    companion object {
        private const val FULL = 255

        private fun tableFor(channel: Int) = IntArray(FULL + 1) { i -> ((i / 255.0) * channel).toInt() }

        fun read(body: ByteArray): UniqueToneRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val tone = AvsFrame.fromConfig(reader.int32())
            val blend = reader.pairBlend() ?: AvsBlendMode.REPLACE
            val invert = reader.int32() != 0
            return if (reader.ok) UniqueToneRenderer(enabled, tone, invert, blend) else null
        }
    }
}

/**
 * Folds one half of the frame onto the other, in any of the four directions.
 *
 * Transcribed from vis_avs `e_mirror.cpp` (BSD; see NOTICE.md). With on-beat
 * random set, nothing folds until the first beat rolls the directions: both
 * ways of an axis enabled makes a three-way choice (none, one, other), a
 * single way a coin flip. A smooth transition walks each fold's strength a
 * sixteenth at a time, one step every `duration` frames, crossfading through
 * 4 bits per channel, so colors are quantized during the sweep as in the
 * original.
 */
internal class MirrorRenderer(
    private val enabled: Boolean,
    private val topToBottom: Boolean,
    private val bottomToTop: Boolean,
    private val leftToRight: Boolean,
    private val rightToLeft: Boolean,
    private val onBeatRandom: Boolean = false,
    private val transitionDuration: Int = 0,
    private val random: Random = Random(SEED),
) : AvsComponentRenderer {
    // zero until the first beat in random mode, like the original's rbeat
    private var curTopToBottom = 0
    private var curBottomToTop = 0
    private var curLeftToRight = 0
    private var curRightToLeft = 0
    private var targetTopToBottom = 0
    private var targetBottomToTop = 0
    private var targetLeftToRight = 0
    private var targetRightToLeft = 0
    private var stepper = 0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        if (onBeatRandom) {
            if (state.beat) {
                rollTargets()
                if (transitionDuration == 0) snapToTargets()
            }
        } else {
            curTopToBottom = full(topToBottom)
            curBottomToTop = full(bottomToTop)
            curLeftToRight = full(leftToRight)
            curRightToLeft = full(rightToLeft)
            snapTargetsToCurrents()
        }
        // e_mirror.cpp folds in this order; a direction mid-transition blends,
        // a settled one copies
        if (curLeftToRight != 0) horizontal(frame, fromLeft = true, curLeftToRight, blending(curLeftToRight, targetLeftToRight))
        if (curRightToLeft != 0) horizontal(frame, fromLeft = false, curRightToLeft, blending(curRightToLeft, targetRightToLeft))
        if (curTopToBottom != 0) vertical(frame, fromTop = true, curTopToBottom, blending(curTopToBottom, targetTopToBottom))
        if (curBottomToTop != 0) vertical(frame, fromTop = false, curBottomToTop, blending(curBottomToTop, targetBottomToTop))
        stepCurrents()
    }

    private fun full(direction: Boolean) = if (direction) FULL_FOLD else 0

    private fun blending(
        cur: Int,
        target: Int,
    ) = transitionDuration > 0 && cur != target

    /** e_mirror.cpp's random_mode: one draw, different bits per axis. */
    private fun rollTargets() {
        val roll = random.nextInt(RANDOM_SPAN)
        when {
            topToBottom && bottomToTop -> {
                val vertical = roll % THREE_WAY
                targetTopToBottom = if (vertical == 2) FULL_FOLD else 0
                targetBottomToTop = if (vertical == 1) FULL_FOLD else 0
            }

            topToBottom -> {
                targetTopToBottom = if (roll and 1 != 0) FULL_FOLD else 0
                targetBottomToTop = 0
            }

            bottomToTop -> {
                targetBottomToTop = if (roll and 1 != 0) FULL_FOLD else 0
                targetTopToBottom = 0
            }

            else -> {
                targetTopToBottom = 0
                targetBottomToTop = 0
            }
        }
        when {
            leftToRight && rightToLeft -> {
                val horizontal = (roll shr 2) % THREE_WAY
                targetLeftToRight = if (horizontal == 2) FULL_FOLD else 0
                targetRightToLeft = if (horizontal == 1) FULL_FOLD else 0
            }

            leftToRight -> {
                targetLeftToRight = if (roll and 1 != 0) FULL_FOLD else 0
                targetRightToLeft = 0
            }

            rightToLeft -> {
                targetRightToLeft = if (roll and 1 != 0) FULL_FOLD else 0
                targetLeftToRight = 0
            }

            else -> {
                targetLeftToRight = 0
                targetRightToLeft = 0
            }
        }
    }

    private fun snapToTargets() {
        curTopToBottom = targetTopToBottom
        curBottomToTop = targetBottomToTop
        curLeftToRight = targetLeftToRight
        curRightToLeft = targetRightToLeft
    }

    private fun snapTargetsToCurrents() {
        targetTopToBottom = curTopToBottom
        targetBottomToTop = curBottomToTop
        targetLeftToRight = curLeftToRight
        targetRightToLeft = curRightToLeft
    }

    /** One step toward each target, every `duration` frames. */
    private fun stepCurrents() {
        if (transitionDuration <= 0) return
        if (stepper > 0) {
            stepper--
            return
        }
        curTopToBottom = curTopToBottom.stepToward(targetTopToBottom)
        curBottomToTop = curBottomToTop.stepToward(targetBottomToTop)
        curLeftToRight = curLeftToRight.stepToward(targetLeftToRight)
        curRightToLeft = curRightToLeft.stepToward(targetRightToLeft)
        stepper = transitionDuration
    }

    private fun Int.stepToward(target: Int) =
        when {
            this < target -> this + 1
            this > target -> this - 1
            else -> this
        }

    private fun vertical(
        frame: AvsFrame,
        fromTop: Boolean,
        divisor: Int,
        blending: Boolean,
    ) {
        for (y in 0 until frame.height / 2) {
            val mirrored = frame.height - 1 - y
            val from = if (fromTop) y else mirrored
            val to = if (fromTop) mirrored else y
            if (blending) {
                for (x in 0 until frame.width) {
                    frame[x, to] = blendAdapt(frame[x, to], frame[x, from], divisor)
                }
            } else {
                // whole rows at a time: a settled vertical fold is a row copy
                frame.pixels.copyInto(frame.pixels, to * frame.width, from * frame.width, (from + 1) * frame.width)
            }
        }
    }

    private fun horizontal(
        frame: AvsFrame,
        fromLeft: Boolean,
        divisor: Int,
        blending: Boolean,
    ) {
        for (x in 0 until frame.width / 2) {
            val mirrored = frame.width - 1 - x
            val from = if (fromLeft) x else mirrored
            val to = if (fromLeft) mirrored else x
            if (blending) {
                for (y in 0 until frame.height) frame[to, y] = blendAdapt(frame[to, y], frame[from, y], divisor)
            } else {
                for (y in 0 until frame.height) frame[to, y] = frame[from, y]
            }
        }
    }

    /** e_mirror.cpp's BLEND_ADAPT: 4 bits per channel, weights summing to 16. */
    private fun blendAdapt(
        a: Int,
        b: Int,
        divisor: Int,
    ) = AvsFrame.OPAQUE or
        (((a shr 4) and NIBBLES) * (FULL_FOLD - divisor) + ((b shr 4) and NIBBLES) * divisor)

    companion object {
        private const val FULL_FOLD = 16
        private const val NIBBLES = 0x0F0F0F
        private const val THREE_WAY = 3

        /** Windows' RAND_MAX + 1, the span the original's rand() rolled over. */
        private const val RANDOM_SPAN = 32768
        private const val SEED = 0x6D697272L

        fun read(body: ByteArray): MirrorRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val directions = reader.int32()
            if (!reader.ok) return null
            // the trailing fields are optional, as e_mirror.cpp's
            // length-guarded reads: absent means off
            val onBeatRandom = reader.int32() != 0
            val smooth = reader.ok && reader.int32() != 0
            val storedDuration = reader.int32()
            val duration =
                when {
                    !smooth -> 0

                    // smooth set but the duration field missing keeps the
                    // pre-assigned 1
                    else -> storedDuration.takeIf { reader.ok } ?: 1
                }
            return MirrorRenderer(
                enabled = enabled,
                topToBottom = directions and 0x01 != 0,
                bottomToTop = directions and 0x02 != 0,
                leftToRight = directions and 0x04 != 0,
                rightToLeft = directions and 0x08 != 0,
                onBeatRandom = onBeatRandom,
                transitionDuration = duration,
            )
        }
    }
}

/** Paints the whole frame, once or every frame. */
internal class ClearScreenRenderer(
    private val enabled: Boolean,
    private val colour: Int,
    private val blend: AvsBlendMode?,
    private val onlyFirst: Boolean,
) : AvsComponentRenderer {
    private var cleared = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        if (onlyFirst && cleared) return
        cleared = true
        fill(frame, colour, blend ?: state.renderBlend)
    }

    companion object {
        fun read(body: ByteArray): ClearScreenRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val colour = AvsFrame.fromConfig(reader.int32())
            val blend = reader.pairBlend()
            val onlyFirst = reader.int32() != 0
            return if (reader.ok) ClearScreenRenderer(enabled, colour, blend, onlyFirst) else null
        }
    }
}

/** Paints the whole frame every so many beats, and never when the count is zero. */
internal class OnBeatClearRenderer(
    private val colour: Int,
    private val blend: AvsBlendMode,
    private val everyBeats: Int,
) : AvsComponentRenderer {
    private var beatsSeen = 0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        // e_onbeatclear.cpp gates on every_n_beats > 0: zero disables the clear
        if (!state.beat || everyBeats <= 0) return
        beatsSeen++
        if (beatsSeen >= everyBeats) {
            beatsSeen = 0
            fill(frame, colour, blend)
        }
    }

    companion object {
        fun read(body: ByteArray): OnBeatClearRenderer? {
            val reader = BodyReader(body)
            val colour = AvsFrame.fromConfig(reader.int32())
            val blend = if (reader.int32() == 1) AvsBlendMode.FIFTY_FIFTY else AvsBlendMode.REPLACE
            val beats = reader.int32()
            return if (reader.ok) OnBeatClearRenderer(colour, blend, beats) else null
        }
    }
}

private fun fill(
    frame: AvsFrame,
    colour: Int,
    blend: AvsBlendMode,
) {
    if (blend == AvsBlendMode.REPLACE) {
        frame.pixels.fill(colour)
        return
    }
    for (i in frame.pixels.indices) {
        frame.pixels[i] = AvsBlend.pixel(blend, colour, frame.pixels[i])
    }
}

/**
 * Squares the picture into cells, more of them on a beat if it was asked to.
 *
 * Transcribed from vis_avs `e_mosaic.cpp` (BSD; see NOTICE.md). The size field
 * (1..100) is how many cells fit along each axis (cells are w/size by h/size
 * in 16.16 fixed point, non-square on non-square frames), and every cell
 * takes the color of its center pixel. At 100 the effect is off. A
 * beat sets the size to the beat value and walks it back linearly over the
 * stored duration, |size - beatSize| / duration per frame.
 */
internal class MosaicRenderer(
    private val enabled: Boolean,
    private val size: Int,
    private val beatSize: Int,
    private val growOnBeat: Boolean,
    private val blend: AvsBlendMode,
    private val beatFrames: Int = 0,
) : AvsComponentRenderer {
    private var curSize = size
    private var cooldown = 0
    private var scratch = IntArray(0)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        if (growOnBeat && state.beat) {
            curSize = beatSize
            cooldown = beatFrames
        } else if (cooldown == 0) {
            curSize = size
        }
        if (curSize < CELLS_OFF) paint(frame)
        decay()
    }

    private fun paint(frame: AvsFrame) {
        // the original divides by the raw size; zero only occurs in a corrupt
        // file and is raised to one
        val cells = curSize.coerceAtLeast(1)
        if (scratch.size != frame.pixels.size) scratch = IntArray(frame.pixels.size)
        frame.pixels.copyInto(scratch)
        val stepX = (frame.width shl 16) / cells
        val stepY = (frame.height shl 16) / cells
        var ypos = stepY ushr 17
        var dypos = 0
        var dest = 0
        for (y in 0 until frame.height) {
            dest = paintRow(frame, ypos * frame.width, stepX, dest)
            dypos += 1 shl 16
            if (dypos >= stepY) {
                ypos += dypos shr 16
                dypos -= stepY
                if (ypos >= frame.height) break
            }
        }
    }

    /**
     * One output row of cells. Returns where the walk stopped: the original's
     * destination pointer runs on across rows, so a row cut short by the
     * sample column reaching the edge starts the next row where it stopped.
     */
    private fun paintRow(
        frame: AvsFrame,
        rowBase: Int,
        stepX: Int,
        start: Int,
    ): Int {
        var dest = start
        var x = frame.width
        var dpos = 0
        var xpos = stepX ushr 17
        while (x > 0) {
            x--
            val cell = scratch[rowBase + xpos]
            frame.pixels[dest] =
                if (blend == AvsBlendMode.REPLACE) cell else AvsBlend.pixel(blend, cell, scratch[dest])
            dest++
            dpos += 1 shl 16
            if (dpos >= stepX) {
                xpos += dpos shr 16
                if (xpos >= frame.width) return dest
                dpos -= stepX
            }
        }
        return dest
    }

    /** The linear walk back to the normal size after a beat. */
    private fun decay() {
        if (cooldown == 0) return
        cooldown--
        if (cooldown > 0) {
            val a = abs(size - beatSize) / beatFrames
            curSize += a * (if (beatSize > size) -1 else 1)
        }
    }

    companion object {
        private const val CELLS_OFF = 100

        fun read(body: ByteArray): MosaicRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val size = reader.int32()
            val beatSize = reader.int32()
            val blend = reader.pairBlend() ?: AvsBlendMode.REPLACE
            val growOnBeat = reader.int32() != 0
            val beatFrames = reader.int32()
            return if (reader.ok) MosaicRenderer(enabled, size, beatSize, growOnBeat, blend, beatFrames) else null
        }
    }
}

/**
 * Alternating bands of color across the frame.
 *
 * Transcribed from vis_avs `e_interleave.cpp` (BSD; see NOTICE.md). The x and
 * y fields are band widths: rows alternate between fully colored bands and
 * untouched ones, each `y` rows tall; within the untouched rows, runs of `x`
 * pixels alternate colored and untouched, both phase-centered by half the
 * frame's remainder. Zero disables an axis; any negative width disables the
 * whole effect. A beat snaps the widths to the beat pair, and every frame the
 * current widths decay toward the normal pair by (duration + 448) / 512, so
 * intermediate widths appear on the way back.
 */
internal class InterleaveRenderer(
    private val enabled: Boolean,
    private val everyX: Int,
    private val everyY: Int,
    private val colour: Int,
    private val blend: AvsBlendMode,
    private val onBeat: Boolean = false,
    private val beatX: Int = 0,
    private val beatY: Int = 0,
    private val beatFrames: Int = 0,
) : AvsComponentRenderer {
    private var curX = everyX.toDouble()
    private var curY = everyY.toDouble()

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        val lerp = (beatFrames + LERP_SPAN - LERP_STICKY) / LERP_SPAN
        curX = curX * lerp + everyX * (1.0 - lerp)
        curY = curY * lerp + everyY * (1.0 - lerp)
        if (state.beat && onBeat) {
            curX = beatX.toDouble()
            curY = beatY.toDouble()
        }
        val strideX = curX.toInt()
        val strideY = curY.toInt()
        if (strideX < 0 || strideY < 0) return
        bands(frame, strideX, strideY)
    }

    private fun bands(
        frame: AvsFrame,
        strideX: Int,
        strideY: Int,
    ) {
        val w = frame.width
        var fillY = strideY != 0
        var edgeY = if (strideY > 0) (frame.height % strideY) / 2 else 0
        val edgeX = if (strideX > 0) (w % strideX) / 2 else 0
        var dest = 0
        for (y in 0 until frame.height) {
            if (strideY != 0) {
                edgeY++
                if (edgeY >= strideY) {
                    fillY = !fillY
                    edgeY = 0
                }
            }
            when {
                fillY -> {
                    fill(frame, dest, w)
                    dest += w
                }

                strideX != 0 -> {
                    dest = runs(frame, dest, strideX, edgeX)
                }

                else -> {
                    dest += w
                }
            }
        }
    }

    /** One unfilled row: runs of strideX pixels, colored first, the first one shortened. */
    private fun runs(
        frame: AvsFrame,
        start: Int,
        strideX: Int,
        edgeX: Int,
    ): Int {
        var dest = start
        var x = frame.width
        var edge = edgeX
        var fillX = true
        while (x > 0) {
            val run = minOf(x, strideX - edge)
            edge = 0
            x -= run
            if (fillX) fill(frame, dest, run)
            dest += run
            fillX = !fillX
        }
        return dest
    }

    private fun fill(
        frame: AvsFrame,
        from: Int,
        count: Int,
    ) {
        for (i in from until from + count) {
            frame.pixels[i] =
                if (blend == AvsBlendMode.REPLACE) colour else AvsBlend.pixel(blend, colour, frame.pixels[i])
        }
    }

    companion object {
        private const val LERP_SPAN = 512.0

        /** At duration 64 the lerp is 1.0 and the beat widths stick until the next beat. */
        private const val LERP_STICKY = 64.0

        fun read(body: ByteArray): InterleaveRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val everyX = reader.int32()
            val everyY = reader.int32()
            val colour = AvsFrame.fromConfig(reader.int32())
            val blend = reader.pairBlend() ?: AvsBlendMode.REPLACE
            val onBeat = reader.int32() != 0
            val beatX = reader.int32()
            val beatY = reader.int32()
            val beatFrames = reader.int32()
            return if (reader.ok) {
                InterleaveRenderer(enabled, everyX, everyY, colour, blend, onBeat, beatX, beatY, beatFrames)
            } else {
                null
            }
        }
    }
}
