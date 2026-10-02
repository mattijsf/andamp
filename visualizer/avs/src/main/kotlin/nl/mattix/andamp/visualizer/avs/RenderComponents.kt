// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// Six components that put marks on a frame: Simple, Timescope, Dot Grid, Ring,
// Starfield and Bass Spin. Super Scope is the scripted one; these are fixed.
//
// Geometry and motion transcribed from vis_avs (BSD; see NOTICE.md):
// e_simple.cpp, e_timescope.cpp, e_dotgrid.cpp, e_ring.cpp, e_starfield.cpp and
// e_bassspin.cpp. Field layouts cross-checked against grandchild/AVS-File-Decoder
// (MIT). AVS hands these components bytes (waveform as signed bytes read
// unsigned via `^128`, so silence is 128, and spectrum magnitudes 0..255), so
// the byte views over this app's float tap are defined here.

/** `fa_data[i] ^ 128` for a waveform channel: 0..255 with silence at 128. */
private fun waveByte(
    audio: AvsAudioFrame,
    index: Int,
): Int {
    val samples = audio.waveform
    if (samples.isEmpty()) return 128
    return ((samples[index.coerceIn(0, samples.size - 1)] * 128f).toInt() + 128).coerceIn(0, 255)
}

/** `fa_data[i]` for the spectrum: a magnitude byte, 0..255. */
private fun specByte(
    audio: AvsAudioFrame,
    index: Int,
): Int {
    val samples = audio.spectrum
    if (samples.isEmpty()) return 0
    return (samples[index.coerceIn(0, samples.size - 1)] * 255f).toInt().coerceIn(0, 255)
}

/** The pi these effects write out by hand: `3.14159`, not `M_PI`. */
private const val AVS_PI = 3.14159

internal enum class AvsPosition { TOP, BOTTOM, CENTER, LEFT, RIGHT }

internal enum class AvsRenderType { DOTS, LINES, SOLID }

/**
 * AVS's fixed scope and analyzer.
 *
 * One int32 carries the mode and the position on the frame. Bit 6 means dots
 * whatever the low two bits say; when it is clear, those two choose between
 * the solid and line forms of the analyzer and the scope.
 *
 * Geometry transcribed from vis_avs `e_simple.cpp` (BSD; see NOTICE.md). The
 * two sources have two different geometries: a waveform scope lives in a
 * half-height band whose top is 0 / h/2 / h/4 for top / bottom / center, with
 * positive samples going down; an analyzer grows bars of up to h/2 from a
 * baseline at h/2 (3h/4 when centered), upward except in the bottom position,
 * where they hang. Only the first 288 waveform samples and 200 spectrum bins
 * are drawn. Dots write straight into the frame; lines and solid columns go
 * through the line drawer, so they use the render mode and line size.
 */
internal class SimpleRenderer(
    private val source: AvsAudioSource,
    private val type: AvsRenderType,
    private val position: AvsPosition,
    private val colours: List<Int>,
) : AvsComponentRenderer {
    private val cycle = ColourCycle(colours)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        // e_simple.cpp: `if (this->config.colors.empty()) return 0;`
        if (colours.isEmpty()) return
        val colour = cycle.next()
        val yPos =
            when (position) {
                AvsPosition.TOP -> 0
                AvsPosition.BOTTOM -> 1
                else -> 2
            }
        val waveform = source == AvsAudioSource.WAVEFORM
        when (type) {
            AvsRenderType.DOTS -> {
                if (waveform) dotScope(frame, audio, colour, yPos) else dotAnalyzer(frame, audio, colour, yPos)
            }

            AvsRenderType.LINES -> {
                if (waveform) lineScope(frame, audio, state, colour, yPos) else lineAnalyzer(frame, audio, state, colour, yPos)
            }

            AvsRenderType.SOLID -> {
                if (waveform) solidScope(frame, audio, state, colour, yPos) else solidAnalyzer(frame, audio, state, colour, yPos)
            }
        }
    }

    /** The top row of the half-height band a waveform scope draws in. */
    private fun bandTop(
        yPos: Int,
        h: Int,
    ) = if (yPos == 2) h / 4 else yPos * h / 2

    private fun dotScope(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        colour: Int,
        yPos: Int,
    ) {
        val h = frame.height
        val yh = bandTop(yPos, h)
        val yscale = h / 2.0 / 256.0
        val xscale = 288.0 / frame.width
        for (x in 0 until frame.width) {
            val r = x * xscale
            val i = r.toInt()
            val s1 = r - i
            val yr = waveByte(audio, i) * (1.0 - s1) + waveByte(audio, i + 1) * s1
            val y = yh + (yr * yscale).toInt()
            // dots bypass the line blend: e_simple writes the framebuffer directly
            if (y in 0 until h) frame[x, y] = colour
        }
    }

    private fun dotAnalyzer(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        colour: Int,
        yPos: Int,
    ) {
        val h = frame.height
        var h2 = h / 2
        var ys = h / 2.0 / 256.0
        val xs = 200.0 / frame.width
        var adj = 1
        if (yPos != 1) {
            ys = -ys
            adj = 0
        }
        if (yPos == 2) h2 -= (ys * 256 / 2).toInt()
        for (x in 0 until frame.width) {
            val r = x * xs
            val i = r.toInt()
            val s1 = r - i
            val yr = specByte(audio, i) * (1.0 - s1) + specByte(audio, i + 1) * s1
            val y = h2 + adj + (yr * ys - 1.0).toInt()
            if (y in 0 until h) frame[x, y] = colour
        }
    }

    private fun lineScope(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
        colour: Int,
        yPos: Int,
    ) {
        val h = frame.height
        val yh = bandTop(yPos, h)
        val yscale = h / 2.0 / 256.0
        val xs = frame.width / 288.0
        var lx = 0
        var ly = yh + (waveByte(audio, 0) * yscale).toInt()
        for (x in 1 until 288) {
            val ox = (x * xs).toInt()
            val oy = yh + (waveByte(audio, x) * yscale).toInt()
            AvsDraw.line(frame, lx, ly, ox, oy, colour, state.lineSize, state.renderBlend, state.renderAdjust)
            lx = ox
            ly = oy
        }
    }

    private fun lineAnalyzer(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
        colour: Int,
        yPos: Int,
    ) {
        val h = frame.height
        var h2 = h / 2
        val xs = frame.width / 288.0 * (288.0 / 200.0)
        var ys = h / 2.0 / 256.0
        if (yPos != 1) ys = -ys
        if (yPos == 2) h2 -= (ys * 256 / 2).toInt()
        var lx = 0
        var ly = h2 + (specByte(audio, 0) * ys).toInt()
        for (x in 1 until 200) {
            val ox = (x * xs).toInt()
            val oy = h2 + (specByte(audio, x) * ys).toInt()
            AvsDraw.line(frame, lx, ly, ox, oy, colour, state.lineSize, state.renderBlend, state.renderAdjust)
            lx = ox
            ly = oy
        }
    }

    private fun solidScope(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
        colour: Int,
        yPos: Int,
    ) {
        val h = frame.height
        val yh = bandTop(yPos, h)
        val yscale = h / 2.0 / 256.0
        val xscale = 288.0 / frame.width
        val base = yh + (yscale * 128.0).toInt()
        for (x in 0 until frame.width) {
            val r = x * xscale
            val i = r.toInt()
            val s1 = r - i
            val yr = waveByte(audio, i) * (1.0 - s1) + waveByte(audio, i + 1) * s1
            val bottom = yh + (yr * yscale).toInt()
            AvsDraw.line(frame, x, base - 1, x, bottom, colour, state.lineSize, state.renderBlend, state.renderAdjust)
        }
    }

    private fun solidAnalyzer(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
        colour: Int,
        yPos: Int,
    ) {
        val h = frame.height
        var h2 = h / 2
        var ys = h / 2.0 / 256.0
        val xs = 200.0 / frame.width
        var adj = 1
        if (yPos != 1) {
            ys = -ys
            adj = 0
        }
        if (yPos == 2) h2 -= (ys * 256 / 2).toInt()
        for (x in 0 until frame.width) {
            val r = x * xs
            val i = r.toInt()
            val s1 = r - i
            val yr = specByte(audio, i) * (1.0 - s1) + specByte(audio, i + 1) * s1
            val top = h2 + adj + (yr * ys - 1.0).toInt()
            AvsDraw.line(frame, x, h2 - adj, x, top, colour, state.lineSize, state.renderBlend, state.renderAdjust)
        }
    }

    companion object {
        private const val DOTS_BIT = 1 shl 6
        private const val WAVEFORM_BIT = 2
        private val POSITIONS = listOf(AvsPosition.TOP, AvsPosition.BOTTOM, AvsPosition.CENTER)

        fun read(body: ByteArray): SimpleRenderer? {
            val reader = BodyReader(body)
            val effect = reader.int32()
            val colours = reader.colourList()
            if (!reader.ok) return null

            val dots = effect and DOTS_BIT != 0
            val source =
                when {
                    dots -> if (effect and WAVEFORM_BIT != 0) AvsAudioSource.WAVEFORM else AvsAudioSource.SPECTRUM
                    effect and 3 >= 2 -> AvsAudioSource.WAVEFORM
                    else -> AvsAudioSource.SPECTRUM
                }
            val type =
                when {
                    dots -> AvsRenderType.DOTS
                    effect and 3 == 1 || effect and 3 == 2 -> AvsRenderType.LINES
                    else -> AvsRenderType.SOLID
                }
            val position = POSITIONS.getOrNull((effect shr 4) and 3) ?: AvsPosition.CENTER
            return SimpleRenderer(source, type, position, colours)
        }
    }
}

/**
 * The spectrum, one column a frame, scrolling.
 *
 * Each frame draws one new column and leaves the earlier columns where they
 * are.
 *
 * Transcribed from vis_avs `e_timescope.cpp` (BSD; see NOTICE.md): the row
 * index times [bands] over the height indexes the spectrum directly, so
 * `bands` chooses how many of the lowest samples the column shows (16 is only
 * bass, 576 the whole spectrum). The column advances before it draws, and the
 * color is scaled by the sample over 256, so even a full-scale band is a step
 * short of the configured color.
 */
internal class TimescopeRenderer(
    private val enabled: Boolean,
    private val colour: Int,
    private val blend: AvsBlendMode?,
    private val bands: Int,
) : AvsComponentRenderer {
    private var column = 0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        column = (column + 1) % frame.width
        val mode = blend ?: state.renderBlend
        val r = redOf(colour)
        val g = greenOf(colour)
        val b = blueOf(colour)
        val count = bands.coerceIn(0, AvsAudioFrame.SAMPLES)

        for (y in 0 until frame.height) {
            val px = specByte(audio, y * count / frame.height)
            val lit = pixelOf(r * px / 256, g * px / 256, b * px / 256)
            AvsDraw.dot(frame, column, y, lit, mode, state.renderAdjust)
        }
    }

    companion object {
        fun read(body: ByteArray): TimescopeRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val colour = AvsFrame.fromConfig(reader.int32())
            val blend = reader.pairBlend()
            reader.int32() // audio channel: this app's tap is mono, see AvsAudioFrame
            val bands = reader.int32()
            return if (reader.ok) TimescopeRenderer(enabled, colour, blend, bands) else null
        }
    }
}

/**
 * A grid of dots that slides.
 *
 * Transcribed from vis_avs `e_dotgrid.cpp` (BSD; see NOTICE.md): the speeds
 * accumulate raw into a 24.8 fixed-point position, so one speed unit is 1/256
 * of a pixel a frame (a speed of 128 moves half a pixel), and the accumulators
 * advance after the frame is drawn. The original clamps spacing at the low
 * end only.
 */
internal class DotGridRenderer(
    private val colours: List<Int>,
    private val spacing: Int,
    private val speedX: Int,
    private val speedY: Int,
    private val blend: AvsBlendMode?,
) : AvsComponentRenderer {
    private val cycle = ColourCycle(colours)
    private var xp = 0
    private var yp = 0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (colours.isEmpty()) return
        val colour = cycle.next()
        val step = spacing.coerceIn(2, MAX_SPACING)
        // the result of the original's `while (yp < 0) yp += step * 256`,
        // without the loop
        if (yp < 0) yp = Math.floorMod(yp, step * 256)
        if (xp < 0) xp = Math.floorMod(xp, step * 256)
        val sy = (yp shr 8) % step
        val sx = (xp shr 8) % step
        val mode = blend ?: state.renderBlend

        var y = sy
        while (y < frame.height) {
            var x = sx
            while (x < frame.width) {
                AvsDraw.dot(frame, x, y, colour, mode, state.renderAdjust)
                x += step
            }
            y += step
        }
        xp += speedX
        yp += speedY
    }

    companion object {
        /**
         * Wider than any frame, so the cap changes nothing that is drawn, and
         * small enough that a spacing in 24.8 fixed point fits an Int.
         */
        private const val MAX_SPACING = 1 shl 16

        fun read(body: ByteArray): DotGridRenderer? {
            val reader = BodyReader(body)
            val colours = reader.colourList()
            val spacing = reader.int32()
            val speedX = reader.int32()
            val speedY = reader.int32()
            val blend = shortBlendOrDefault(reader.int32())
            return if (reader.ok) DotGridRenderer(colours, spacing, speedX, speedY, blend) else null
        }
    }
}

/**
 * A circle whose edge is the waveform.
 *
 * Transcribed from vis_avs `e_ring.cpp` (BSD; see NOTICE.md). The audio scales
 * the whole radius (`size/32` of the frame's short side times
 * `0.1 + 0.9 * value`), so a silent spectrum ring is a tenth of its size, and
 * size runs past 32, letting the ring outgrow the frame. Eighty segments walk
 * clockwise, the sample index folding at forty so the ring mirrors about the
 * horizontal axis; spectrum mode averages bin pairs, so only the first 82
 * bins appear. A segment with both endpoints off the frame is skipped, even
 * if the line between them would cross it, as in the original.
 */
internal class RingRenderer(
    private val source: AvsAudioSource,
    private val position: AvsPosition,
    private val colours: List<Int>,
    private val size: Int,
) : AvsComponentRenderer {
    private val cycle = ColourCycle(colours)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (colours.isEmpty()) return
        val colour = cycle.next()
        val w = frame.width
        val h = frame.height
        val fsize = size / 32.0
        val sizePx = minOf(h * fsize, w * fsize)
        val cX =
            when (position) {
                AvsPosition.LEFT -> w / 4
                AvsPosition.RIGHT -> w / 2 + w / 4
                else -> w / 2
            }
        val cY = h / 2

        var a = 0.0
        var sca = value(audio, 0)
        var lx = (cX + cos(a) * sizePx * sca).toInt()
        var ly = (cY + sin(a) * sizePx * sca).toInt()
        for (q in 1..80) {
            a -= AVS_PI * 2.0 / 80.0
            sca = value(audio, if (q > 40) 80 - q else q)
            val tx = (cX + cos(a) * sizePx * sca).toInt()
            val ty = (cY + sin(a) * sizePx * sca).toInt()
            val visible = (tx in 0 until w && ty in 0 until h) || (lx in 0 until w && ly in 0 until h)
            if (visible) {
                AvsDraw.line(frame, tx, ty, lx, ly, colour, state.lineSize, state.renderBlend, state.renderAdjust)
            }
            lx = tx
            ly = ty
        }
    }

    private fun value(
        audio: AvsAudioFrame,
        q: Int,
    ): Double =
        if (source == AvsAudioSource.WAVEFORM) {
            0.1 + (waveByte(audio, q) / 255.0) * 0.9
        } else {
            0.1 + ((specByte(audio, q * 2) / 2 + specByte(audio, q * 2 + 1) / 2) / 255.0) * 0.9
        }

    companion object {
        private val POSITIONS = listOf(AvsPosition.LEFT, AvsPosition.RIGHT, AvsPosition.CENTER)

        fun read(body: ByteArray): RingRenderer? {
            val reader = BodyReader(body)
            val flags = reader.int32()
            val colours = reader.colourList()
            val size = reader.int32()
            val source = if (reader.int32() == 1) AvsAudioSource.SPECTRUM else AvsAudioSource.WAVEFORM
            val position = POSITIONS.getOrNull((flags shr 4) and 3) ?: AvsPosition.CENTER
            return if (reader.ok) RingRenderer(source, position, colours, size) else null
        }
    }
}

/**
 * Stars flying toward the viewer.
 *
 * Transcribed from vis_avs `e_starfield.cpp` (BSD; see NOTICE.md). The stored
 * star count is a density at 512x384, scaled to the frame's own area and capped
 * at 4095. Stars live in pixel space: an (x, y) offset from the center and a
 * depth 0..255, projected as `offset * 128 / z`, so a fresh star at the far
 * plane sits at half its offset and flies outward. Each star has its own speed
 * 0.1..0.9, which scales both its motion and its brightness
 * `(255 - z) * speed`. A star that leaves the frame or arrives at the camera
 * respawns at the far plane, keeping its speed. On a beat the field jumps to
 * the beat speed, which may be slower than the warp speed, and ramps linearly
 * back over the configured frames. A non-white color is mixed in with the
 * original's staircase blend, which keeps gray stars visible even when the
 * color is black.
 */
internal class StarfieldRenderer(
    private val enabled: Boolean,
    private val colour: Int,
    private val blend: AvsBlendMode?,
    private val warpSpeed: Float,
    private val stars: Int,
    private val onBeat: Boolean,
    private val beatSpeed: Float,
    private val beatFrames: Int,
) : AvsComponentRenderer {
    private val random = Random(SEED)
    private val starX = IntArray(MAX_STARS)
    private val starY = IntArray(MAX_STARS)
    private val starZ = FloatArray(MAX_STARS)
    private val starSpeed = FloatArray(MAX_STARS)
    private var absStars = 0
    private var width = 0
    private var height = 0
    private var xOff = 0
    private var yOff = 0
    private var currentSpeed = warpSpeed
    private var beatSpeedDiff = 0f
    private var beatCooldown = 0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        if (onBeat && state.beat) {
            currentSpeed = beatSpeed
            beatSpeedDiff = (warpSpeed - currentSpeed) / beatFrames.toFloat()
            beatCooldown = beatFrames
        }
        if (width != frame.width || height != frame.height) {
            width = frame.width
            height = frame.height
            xOff = width / 2
            yOff = height / 2
            placeStars()
        }
        val mode = blend ?: AvsBlendMode.REPLACE

        for (i in 0 until absStars) {
            val z = starZ[i].toInt()
            if (z <= 0) {
                respawn(i)
                continue
            }
            val nx = (starX[i] shl 7) / z + xOff
            val ny = (starY[i] shl 7) / z + yOff
            // strictly inside: e_starfield treats row and column zero as off-screen
            if (nx !in 1 until width || ny !in 1 until height) {
                respawn(i)
                continue
            }
            val level = ((255 - z) * starSpeed[i]).toInt()
            val grey = level or (level shl 8) or (level shl 16)
            val mixed = if (colour and 0xFFFFFF != 0xFFFFFF) roughMix(grey, colour, level shr 4) else grey
            AvsDraw.dot(frame, nx, ny, AvsFrame.OPAQUE or mixed, mode, state.renderAdjust)
            starZ[i] -= starSpeed[i] * currentSpeed
        }

        if (beatCooldown == 0) {
            currentSpeed = warpSpeed
        } else {
            currentSpeed = max(0f, currentSpeed + beatSpeedDiff)
            beatCooldown--
        }
    }

    /**
     * `blend_adjustable_rough`: a 16-step staircase mix, in which gray shows
     * through even a black color.
     */
    private fun roughMix(
        grey: Int,
        tint: Int,
        v: Int,
    ) = ((grey shr 4) and 0x0F0F0F) * (16 - v) + ((tint shr 4) and 0x0F0F0F) * v

    private fun placeStars() {
        absStars = (stars.toLong() * width * height / (512L * 384L)).toInt().coerceAtMost(MAX_STARS)
        for (i in 0 until absStars) {
            starX[i] = random.nextInt(width) - xOff
            starY[i] = random.nextInt(height) - yOff
            starZ[i] = random.nextInt(255).toFloat()
            starSpeed[i] = (random.nextInt(9) + 1) / 10f
        }
    }

    /** A star reborn at the far plane: new offsets, depth 255, its speed kept. */
    private fun respawn(i: Int) {
        starX[i] = random.nextInt(width) - xOff
        starY[i] = random.nextInt(height) - yOff
        starZ[i] = FAR_PLANE
    }

    companion object {
        private const val MAX_STARS = 4095
        private const val FAR_PLANE = 255f
        private const val SEED = 0x73746172L

        fun read(body: ByteArray): StarfieldRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val colour = AvsFrame.fromConfig(reader.int32())
            val blend = reader.pairBlend()
            val warpSpeed = reader.float()
            val stars = reader.int32()
            val onBeat = reader.int32() != 0
            val beatSpeed = reader.float()
            val beatFrames = reader.int32()
            return if (reader.ok) {
                StarfieldRenderer(enabled, colour, blend, warpSpeed, stars, onBeat, beatSpeed, beatFrames)
            } else {
                null
            }
        }
    }
}

/**
 * Two arms that spin with the bass.
 *
 * Transcribed from vis_avs `e_bassspin.cpp` (BSD; see NOTICE.md). Each arm sums
 * its channel's first 44 spectrum bytes and judges the sum against the
 * previous one, which is a single shared member, so the right arm compares
 * against the left arm's reading, as in the original. That loudness drives an
 * exponentially smoothed angular velocity and scales the reach, which
 * collapses to nothing in silence. An arm has a tip and its mirror opposite;
 * outline mode draws a spoke to each plus a rim line from where each tip was
 * last frame, and filled mode sweeps a triangle from the center through the
 * old and new tips. The config gating uses bit 0 for the left arm and bit 1
 * for the right, as in the original `r_bspin.cpp`; vis_avs gates arm 0 on
 * `enabled_right`, which is not copied.
 */
internal class BassSpinRenderer(
    private val left: Boolean,
    private val right: Boolean,
    private val colourLeft: Int,
    private val colourRight: Int,
    private val triangles: Boolean,
) : AvsComponentRenderer {
    private var lastLoudness = 0
    private val velocity = DoubleArray(2)
    private val angle = doubleArrayOf(AVS_PI, 0.0)
    private val tipX = IntArray(2)
    private val tipY = IntArray(2)
    private val oppositeX = IntArray(2)
    private val oppositeY = IntArray(2)

    // the triangle filler's scratch, reused so that no point list is allocated
    // per frame
    private val corners = IntArray(6)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        for (arm in 0..1) {
            if (if (arm == 0) !left else !right) continue
            renderArm(frame, audio, state, arm)
        }
    }

    private fun renderArm(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
        arm: Int,
    ) {
        val w = frame.width
        val h = frame.height
        val ss = minOf(h / 2, w * 3 / 8)
        val cX = if (arm == 0) w / 2 - ss / 2 else w / 2 + ss / 2
        val colour = if (arm == 0) colourLeft else colourRight

        var d = 0
        for (i in 0 until 44) d += specByte(audio, i)
        var a = d * 512 / (lastLoudness + 30 * 256)
        lastLoudness = d
        if (a > 255) a = 255
        velocity[arm] = 0.7 * (max(a - 104, 12) / 96.0) + 0.3 * velocity[arm]
        angle[arm] += AVS_PI / 6.0 * velocity[arm] * if (arm == 0) -1.0 else 1.0

        val reach = ss * (a / 256.0)
        val xp = (cos(angle[arm]) * reach).toInt()
        val yp = (sin(angle[arm]) * reach).toInt()
        val newTipX = cX + xp
        val newTipY = h / 2 + yp
        val newOppX = cX - xp
        val newOppY = h / 2 - yp

        if (!triangles) {
            if (tipX[arm] != 0 || tipY[arm] != 0) {
                AvsDraw.line(
                    frame,
                    tipX[arm],
                    tipY[arm],
                    newTipX,
                    newTipY,
                    colour,
                    state.lineSize,
                    state.renderBlend,
                    state.renderAdjust,
                )
            }
            tipX[arm] = newTipX
            tipY[arm] = newTipY
            AvsDraw.line(frame, cX, h / 2, newTipX, newTipY, colour, state.lineSize, state.renderBlend, state.renderAdjust)
            if (oppositeX[arm] != 0 || oppositeY[arm] != 0) {
                AvsDraw.line(
                    frame,
                    oppositeX[arm],
                    oppositeY[arm],
                    newOppX,
                    newOppY,
                    colour,
                    state.lineSize,
                    state.renderBlend,
                    state.renderAdjust,
                )
            }
            oppositeX[arm] = newOppX
            oppositeY[arm] = newOppY
            AvsDraw.line(frame, cX, h / 2, newOppX, newOppY, colour, state.lineSize, state.renderBlend, state.renderAdjust)
        } else {
            if (tipX[arm] != 0 || tipY[arm] != 0) {
                fillTriangle(frame, state, colour, cX, h / 2, tipX[arm], tipY[arm], newTipX, newTipY)
            }
            tipX[arm] = newTipX
            tipY[arm] = newTipY
            if (oppositeX[arm] != 0 || oppositeY[arm] != 0) {
                fillTriangle(frame, state, colour, cX, h / 2, oppositeX[arm], oppositeY[arm], newOppX, newOppY)
            }
            oppositeX[arm] = newOppX
            oppositeY[arm] = newOppY
        }
    }

    /**
     * `E_BassSpin::render_triangle`: sort the three points by y, then walk the
     * rows interpolating both edges in 16.16 fixed point, filling each span
     * with the default blend. The original lets a left-clipped span spill past
     * the row's right edge into the next row's start; here the span is clamped
     * to the row.
     */
    @Suppress("LongParameterList") // three points plus the draw context; a point list would allocate per frame
    private fun fillTriangle(
        frame: AvsFrame,
        state: AvsRenderState,
        colour: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ) {
        val p = corners
        p[0] = x0
        p[1] = y0
        p[2] = x1
        p[3] = y1
        p[4] = x2
        p[5] = y2
        repeat(2) {
            if (p[1] > p[3]) swapPoints(p, 0, 1)
            if (p[3] > p[5]) swapPoints(p, 1, 2)
        }

        var xa = p[0] shl 16
        var xb = p[0] shl 16
        var dx1 = if (p[1] < p[3]) ((p[2] - p[0]) shl 16) / (p[3] - p[1]) else 0
        val dx2 = if (p[1] < p[5]) ((p[4] - p[0]) shl 16) / (p[5] - p[1]) else 0
        val maxY = minOf(p[5], frame.height)
        var y = p[1]
        while (y < maxY) {
            if (y == p[3]) {
                if (y == p[5]) return
                xa = p[2] shl 16
                dx1 = ((p[4] - p[2]) shl 16) / (p[5] - p[3])
            }
            if (y >= 0) {
                val from = (minOf(xa, xb) - 32768) shr 16
                var length = ((maxOf(xa, xb) + 32768) shr 16) - from
                if (length < 0) length = -length
                if (length == 0) length = 1
                var x = maxOf(from, 0)
                val end = minOf(from + length, frame.width)
                while (x < end) {
                    AvsDraw.dot(frame, x, y, colour, state.renderBlend, state.renderAdjust)
                    x++
                }
            }
            xa += dx1
            xb += dx2
            y++
        }
    }

    private fun swapPoints(
        p: IntArray,
        a: Int,
        b: Int,
    ) {
        val ax = a * 2
        val bx = b * 2
        var t = p[bx]
        p[bx] = p[ax]
        p[ax] = t
        t = p[bx + 1]
        p[bx + 1] = p[ax + 1]
        p[ax + 1] = t
    }

    companion object {
        fun read(body: ByteArray): BassSpinRenderer? {
            val reader = BodyReader(body)
            val flags = reader.int32()
            val colourLeft = AvsFrame.fromConfig(reader.int32())
            val colourRight = AvsFrame.fromConfig(reader.int32())
            // e_bassspin.cpp: `GET_INT() ? BASSSPIN_MODE_FILLED : BASSSPIN_MODE_OUTLINE`
            val triangles = reader.int32() != 0
            return if (reader.ok) {
                BassSpinRenderer(flags and 1 != 0, flags and 2 != 0, colourLeft, colourRight, triangles)
            } else {
                null
            }
        }
    }
}
