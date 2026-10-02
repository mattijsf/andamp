// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Four warps: two blitters with a fixed zoom and rotation, two driven by
// ns-eel. The blitters and the distance modifier are fixed-point
// transcriptions from vis_avs (BSD; see NOTICE.md). Each body's trailing
// bilinear flag is not read: sampling is nearest-neighbor everywhere.

/**
 * Zooms the frame into itself, harder on a beat when the preset asks for it.
 *
 * Transcribed from `e_blitterfeedback.cpp`. The zoom field is a sampling step:
 * below 32 the step is `(zoom+32)/64`, which is under one, so the center of
 * the frame expands to fill it; 32 is identity. Above 32 the step is
 * `(zoom+96)/128` and only a shrunken copy is written, centered over the
 * otherwise untouched frame, so the old picture stays around it. A beat snaps
 * the working zoom to the beat value, which then relaxes back toward the base
 * zoom by 3 units a frame.
 */
internal class BlitterFeedbackRenderer(
    private val zoom: Int,
    private val beatZoom: Int,
    private val onBeat: Boolean,
    private val blend: AvsBlendMode,
) : AvsComponentRenderer {
    private var currentZoom = zoom
    private var scratch: AvsFrame? = null

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (state.beat && onBeat) currentZoom = beatZoom
        val target: Int
        if (zoom < beatZoom) {
            target = maxOf(currentZoom, zoom)
            currentZoom -= DECAY
        } else {
            target = minOf(currentZoom, zoom)
            currentZoom += DECAY
        }
        val z = target.coerceAtLeast(0)
        when {
            z < IDENTITY -> blitterNormal(frame, z)
            z > IDENTITY -> blitterOut(frame, z)
        }
    }

    /** The whole frame resampled at a step under one: the center expands. */
    private fun blitterNormal(
        frame: AvsFrame,
        zoom: Int,
    ) {
        val w = frame.width
        val h = frame.height
        val source = scratchFor(frame)
        source.copyFrom(frame)
        val step = ((zoom + IDENTITY) shl FIXED_BITS) / MAGNIFY_RANGE
        val startX = ((w shl FIXED_BITS) - step * w) / 2
        var sy = ((h shl FIXED_BITS) - step * h) / 2
        val fiftyFifty = blend == AvsBlendMode.FIFTY_FIFTY
        for (y in 0 until h) {
            var sx = startX
            val sourceRow = (sy shr FIXED_BITS) * w
            sy += step
            val destRow = y * w
            for (x in 0 until w) {
                val pixel = source.pixels[sourceRow + (sx shr FIXED_BITS)]
                frame.pixels[destRow + x] =
                    if (fiftyFifty) {
                        AvsBlend.pixel(AvsBlendMode.FIFTY_FIFTY, pixel, source.pixels[destRow + x])
                    } else {
                        pixel
                    }
                sx += step
            }
        }
    }

    /** A shrunken copy pasted over the center; the border keeps the old frame. */
    private fun blitterOut(
        frame: AvsFrame,
        zoom: Int,
    ) {
        val w = frame.width
        val h = frame.height
        val step = (zoom + SHRINK_BIAS) shl (FIXED_BITS - SHRINK_ADJ)
        val xLength = ((w shl FIXED_BITS) / step) and ALIGN4
        val yLength = (h shl FIXED_BITS) / step
        if (xLength >= w || yLength >= h) return
        if (xLength <= 0 || yLength <= 0) return
        val startX = (w - xLength) / 2
        val startY = (h - yLength) / 2
        val source = scratchFor(frame)
        source.copyFrom(frame)
        val fiftyFifty = blend == AvsBlendMode.FIFTY_FIFTY
        var sy = HALF_PIXEL
        for (y in 0 until yLength) {
            var sx = HALF_PIXEL
            val sourceRow = (sy shr FIXED_BITS) * w
            sy += step
            val destRow = (startY + y) * w + startX
            for (x in 0 until xLength) {
                val pixel = source.pixels[sourceRow + (sx shr FIXED_BITS)]
                frame.pixels[destRow + x] =
                    if (fiftyFifty) {
                        AvsBlend.pixel(AvsBlendMode.FIFTY_FIFTY, pixel, source.pixels[destRow + x])
                    } else {
                        pixel
                    }
                sx += step
            }
        }
    }

    private fun scratchFor(frame: AvsFrame): AvsFrame {
        val existing = scratch
        if (existing != null && existing.sameSizeAs(frame)) return existing
        return AvsFrame(frame.width, frame.height).also { scratch = it }
    }

    companion object {
        private const val IDENTITY = 32
        private const val DECAY = 3
        private const val FIXED_BITS = 16
        private const val HALF_PIXEL = 32768
        private const val MAGNIFY_RANGE = 64
        private const val SHRINK_ADJ = 7
        private const val SHRINK_BIAS = (1 shl SHRINK_ADJ) - IDENTITY
        private const val ALIGN4 = -4 // AVS's `& ~3`: the shrunken box is a multiple of four wide

        /**
         * zoom, beat zoom, blend, on-beat, bilinear. The fourth int is read as
         * the on-beat flag the original wrote there; vis_avs reads it as a
         * second beat zoom.
         */
        fun read(body: ByteArray): BlitterFeedbackRenderer? {
            val reader = BodyReader(body)
            val zoom = reader.int32()
            val beatZoom = reader.int32()
            val blend = if (reader.int32() == 1) AvsBlendMode.FIFTY_FIFTY else AvsBlendMode.REPLACE
            val onBeat = reader.int32() != 0
            // bilinear follows and is not read
            return if (reader.ok) BlitterFeedbackRenderer(zoom, beatZoom, onBeat, blend) else null
        }
    }
}

/**
 * A zoom that also turns.
 *
 * Transcribed from `e_rotoblitter.cpp`. The sampling scale is the file's zoom
 * over 31 (identity at 31, shrinking as it grows), and each frame rotates the
 * current frame contents by a constant `(rotate - 32)` degrees, so feedback
 * makes the total grow linearly. On a beat the reverse flag flips the
 * direction, eased at `1/(1 + 4*speed)` per frame; the beat zoom snaps the
 * working zoom, which relaxes back by 3 a frame. The sampling always tiles,
 * with a period one short of the frame on each axis.
 */
internal class RotoBlitterRenderer(
    zoom: Int,
    rotate: Int,
    private val blend: AvsBlendMode,
    private val onBeatReverse: Boolean = false,
    private val reversalSpeed: Int = 0,
    beatZoom: Int = 0,
    private val onBeat: Boolean = false,
) : AvsComponentRenderer {
    private val zoomConfig = zoom - ZOOM_IDENTITY
    private val beatZoomConfig = beatZoom - ZOOM_IDENTITY
    private val rotateDegrees = rotate - ROTATE_STILL
    private var currentZoom = zoomConfig
    private var direction = 1
    private var currentRotation = 1.0
    private var scratch: AvsFrame? = null

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        advanceRotation(state.beat)
        val factor = 1.0 + advanceZoom(state.beat) / ZOOM_SCALE
        val theta = rotateDegrees * currentRotation * PI / DEGREES_HALF_TURN
        resample(frame, cos(theta) * factor, sin(theta) * factor)
    }

    private fun advanceRotation(beat: Boolean) {
        if (beat && onBeatReverse) direction = -direction
        if (!onBeatReverse) direction = 1
        currentRotation += 1.0 / (1 + reversalSpeed * 4) * (direction - currentRotation)
        if (direction > 0 && currentRotation > direction) currentRotation = direction.toDouble()
        if (direction < 0 && currentRotation < direction) currentRotation = direction.toDouble()
    }

    private fun advanceZoom(beat: Boolean): Int {
        if (beat && onBeat) currentZoom = beatZoomConfig
        return if (zoomConfig < beatZoomConfig) {
            val zoom = maxOf(currentZoom, zoomConfig)
            if (currentZoom > zoomConfig) currentZoom -= DECAY
            zoom
        } else {
            val zoom = minOf(currentZoom, zoomConfig)
            if (currentZoom < zoomConfig) currentZoom += DECAY
            zoom
        }
    }

    private fun resample(
        frame: AvsFrame,
        cosine: Double,
        sine: Double,
    ) {
        val w = frame.width
        val h = frame.height
        val dsDx = (cosine * FIXED_ONE).toInt()
        val dtDy = (cosine * FIXED_ONE).toInt()
        val dsDy = -(sine * FIXED_ONE).toInt()
        val dtDx = (sine * FIXED_ONE).toInt()

        var sStart = -(((w - 1) / 2) * dsDx + ((h - 1) / 2) * dsDy) + (w - 1) * (HALF_PIXEL + CENTER_BIAS)
        var tStart = -(((w - 1) / 2) * dtDx + ((h - 1) / 2) * dtDy) + (h - 1) * (HALF_PIXEL + CENTER_BIAS)
        val ds = (w - 1) shl FIXED_BITS
        val dt = (h - 1) shl FIXED_BITS
        // a step past a whole frame per pixel cannot tile; AVS leaves stale
        // output standing in that case, we leave the frame alone instead
        if (dsDx <= -ds || dsDx >= ds) return
        if (dtDx <= -dt || dtDx >= dt) return

        val source = scratchFor(frame)
        source.copyFrom(frame)
        val fiftyFifty = blend == AvsBlendMode.FIFTY_FIFTY
        var s = sStart
        var t = tStart
        for (y in 0 until h) {
            s %= ds
            t %= dt
            if (s < 0) s += ds
            if (t < 0) t += dt
            val destRow = y * w
            for (x in 0 until w) {
                if (s < 0) {
                    s += ds
                } else if (s >= ds) {
                    s -= ds
                }
                if (t < 0) {
                    t += dt
                } else if (t >= dt) {
                    t -= dt
                }
                val pixel = source.pixels[(s shr FIXED_BITS) + (t shr FIXED_BITS) * w]
                frame.pixels[destRow + x] =
                    if (fiftyFifty) {
                        AvsBlend.pixel(AvsBlendMode.FIFTY_FIFTY, pixel, source.pixels[destRow + x])
                    } else {
                        pixel
                    }
                s += dsDx
                t += dtDx
            }
            sStart += dsDy
            tStart += dtDy
            s = sStart
            t = tStart
        }
    }

    private fun scratchFor(frame: AvsFrame): AvsFrame {
        val existing = scratch
        if (existing != null && existing.sameSizeAs(frame)) return existing
        return AvsFrame(frame.width, frame.height).also { scratch = it }
    }

    companion object {
        /** The file's zoom is offset by the parameter minimum, -31: identity is 31 on disk. */
        private const val ZOOM_IDENTITY = 31
        private const val ZOOM_SCALE = 31.0
        private const val ROTATE_STILL = 32
        private const val DECAY = 3
        private const val FIXED_BITS = 16
        private const val FIXED_ONE = 65536.0
        private const val HALF_PIXEL = 32768
        private const val CENTER_BIAS = 1 shl 20
        private const val DEGREES_HALF_TURN = 180.0

        fun read(body: ByteArray): RotoBlitterRenderer? {
            val reader = BodyReader(body)
            val zoom = reader.int32()
            val rotate = reader.int32()
            val blend = if (reader.int32() == 1) AvsBlendMode.FIFTY_FIFTY else AvsBlendMode.REPLACE
            val onBeatReverse = reader.int32() != 0
            val reversalSpeed = reader.int32()
            val beatZoom = reader.int32()
            val onBeat = reader.int32() != 0
            // bilinear follows and is not read
            return if (reader.ok) {
                RotoBlitterRenderer(zoom, rotate, blend, onBeatReverse, reversalSpeed, beatZoom, onBeat)
            } else {
                null
            }
        }
    }
}

/**
 * Slides the whole frame by an amount its code works out each frame.
 *
 * Three code sections: there is no per-point one, because the shift is the
 * same everywhere, so the code runs once a frame.
 *
 * Transcribed from `e_dynamicshift.cpp`: `x` and `y` are zeroed only before
 * the init code, which runs on the first frame and again when the frame
 * changes size, so a shift persists until the preset's code changes it;
 * `alpha` resets to 0.5 every frame. With the blend flag on, the shifted image
 * mixes over the unshifted frame at the script's `alpha`: at or below 0 the
 * component does nothing, at or above 1 it is a plain replace.
 */
internal class DynamicShiftRenderer(
    init: String,
    perFrame: String,
    onBeat: String,
    private val blend: AvsBlendMode,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    private val x = eel.variable("x")
    private val y = eel.variable("y")
    private val beat = eel.variable("b")
    private val width = eel.variable("w")
    private val height = eel.variable("h")
    private val alpha = eel.variable("alpha")

    private val initCode = compile(init)
    private val frameCode = compile(perFrame)
    private val beatCode = compile(onBeat)

    override val errors: List<String> =
        buildList {
            if (init.isNotBlank() && initCode == null) add("init")
            if (perFrame.isNotBlank() && frameCode == null) add("per frame")
            if (onBeat.isNotBlank() && beatCode == null) add("on beat")
        }

    private var scratch: AvsFrame? = null
    private var lastWidth = 0
    private var lastHeight = 0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (lastWidth != frame.width || lastHeight != frame.height) {
            lastWidth = frame.width
            lastHeight = frame.height
            x.value = 0.0
            y.value = 0.0
            alpha.value = WarpMesh.DEFAULT_ALPHA
            initCode?.run()
        }
        // x and y are not zeroed here: e_dynamicshift.cpp only zeroes them in
        // its init/resize block. Alpha does reset per frame.
        width.value = frame.width.toDouble()
        height.value = frame.height.toDouble()
        beat.value = if (state.beat) 1.0 else 0.0
        alpha.value = WarpMesh.DEFAULT_ALPHA
        frameCode?.run()
        if (state.beat) beatCode?.run()

        // no zero-shift shortcut: AVS runs the copy (or the slightly lossy
        // self-blend, when blending) even when the code asked for no movement
        val source = scratchFor(frame)
        source.copyFrom(frame)
        shift(frame, source, x.value.toInt(), y.value.toInt(), blend == AvsBlendMode.FIFTY_FIFTY, alpha.value)
    }

    private fun scratchFor(frame: AvsFrame): AvsFrame {
        val existing = scratch
        if (existing != null && existing.sameSizeAs(frame)) return existing
        return AvsFrame(frame.width, frame.height).also { scratch = it }
    }

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    companion object {
        private const val SECTIONS = 3
        private const val FULL = 255

        /**
         * The pixel work alone, without the evaluator: [frame] takes the
         * shifted image, [source] is the unshifted copy it reads.
         */
        internal fun shift(
            frame: AvsFrame,
            source: AvsFrame,
            byX: Int,
            byY: Int,
            fiftyFifty: Boolean,
            alpha: Double,
        ) {
            var blended = fiftyFifty
            var level = FULL / 2
            if (blended) {
                level = (alpha * FULL).toInt()
                if (level <= 0) return
                if (level >= FULL) blended = false
            }
            if (blended) {
                shiftBlended(frame, source, byX, byY, level)
            } else {
                shiftRows(frame, source, byX, byY)
            }
        }

        /** The replace path is a row copy, not a pixel loop. Vacated space goes black. */
        private fun shiftRows(
            frame: AvsFrame,
            source: AvsFrame,
            byX: Int,
            byY: Int,
        ) {
            val fromX = (-byX).coerceIn(0, frame.width)
            val spanStart = byX.coerceIn(0, frame.width)
            val span = (frame.width - fromX - spanStart).coerceAtLeast(0)
            for (destY in 0 until frame.height) {
                val sy = destY - byY
                val row = destY * frame.width
                frame.pixels.fill(AvsFrame.OPAQUE, row, row + frame.width)
                if (sy !in 0 until frame.height || span == 0) continue
                val sourceRow = sy * frame.width + fromX
                source.pixels.copyInto(frame.pixels, row + spanStart, sourceRow, sourceRow + span)
            }
        }

        /**
         * The blended path: shifted image over the original at [level], black
         * blended into the vacated edges at the same level.
         *
         * For a leftward shift, `e_dynamicshift.cpp` zeroes its column offset
         * after the first blended row (`else xa = 0;`), so only that row is
         * shifted and the rows below blend the frame over itself unmoved. That
         * is transcribed as it is.
         */
        private fun shiftBlended(
            frame: AvsFrame,
            source: AvsFrame,
            byX: Int,
            byY: Int,
            level: Int,
        ) {
            val w = frame.width
            val h = frame.height
            var xa = byX.coerceAtMost(w)
            val ya = byY.coerceAtMost(h)
            val endY = (h + ya).coerceAtMost(h)
            val endX = (w + xa).coerceAtMost(w)
            var out = 0
            var y = 0
            if (ya > 0) {
                out = blendBlack(frame, source, 0, ya * w, level)
                y = ya
            }
            while (y < endY) {
                var sourceIndex = (y - byY) * w
                var written = 0
                if (xa < 0) {
                    sourceIndex -= xa
                } else if (xa > 0) {
                    out = blendBlack(frame, source, out, xa, level)
                    written = xa
                }
                if (xa < 0) xa = 0
                var count = (endX - xa).coerceAtLeast(0)
                written += count
                while (count-- > 0) {
                    frame.pixels[out] =
                        AvsBlend.pixel(AvsBlendMode.ADJUSTABLE, source.pixels[sourceIndex++], source.pixels[out], level)
                    out++
                }
                if (written < w) out = blendBlack(frame, source, out, w - written, level)
                y++
            }
            if (y < h) blendBlack(frame, source, out, h * w - out, level)
        }

        private fun blendBlack(
            frame: AvsFrame,
            source: AvsFrame,
            from: Int,
            count: Int,
            level: Int,
        ): Int {
            for (i in from until from + count) {
                frame.pixels[i] = AvsBlend.pixel(AvsBlendMode.ADJUSTABLE, AvsFrame.OPAQUE, source.pixels[i], level)
            }
            return from + count
        }

        /** Stored init, per-frame, on-beat (the decoder's `CodeIFB`), then blend and bilinear. */
        fun read(
            body: ByteArray,
            eel: Eel,
        ): DynamicShiftRenderer? {
            if (body.isEmpty()) return null
            val current = body[0].toInt() == 1
            val reader = CodeSectionReader(body, if (current) 1 else 0, current)
            val code = List(SECTIONS) { reader.next() }
            val rest = reader.rest()
            val blend = if (rest.int32() == 1) AvsBlendMode.FIFTY_FIFTY else AvsBlendMode.REPLACE
            // bilinear follows and is not read
            return if (reader.ok && rest.ok) DynamicShiftRenderer(code[0], code[1], code[2], blend, eel) else null
        }
    }
}

/**
 * Pulls pixels in or out along their own radius.
 *
 * Transcribed from `e_dynamicdistancemodifier.cpp`. The point code runs once
 * per integer radius, with only `d` (radius over the half-diagonal) and `b`
 * registered, and its answers build a per-radius multiplier table that a
 * radial pixel loop then applies, through the same integer square-root
 * approximation AVS uses. There is no wrap option; the edge clamps.
 */
internal class DynamicDistanceModifierRenderer(
    private val config: DdmConfig,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    private val d = eel.variable("d")
    private val b = eel.variable("b")

    private val init = compile(config.init)
    private val perFrame = compile(config.perFrame)
    private val onBeat = compile(config.onBeat)
    private val perPoint = compile(config.perPoint)

    override val errors: List<String> =
        buildList {
            if (config.init.isNotBlank() && init == null) add("init")
            if (config.perFrame.isNotBlank() && perFrame == null) add("per frame")
            if (config.onBeat.isNotBlank() && onBeat == null) add("on beat")
            if (config.perPoint.isNotBlank() && perPoint == null) add("per point")
        }

    private var table = IntArray(0)
    private var maxDistance = 0.0
    private var lastWidth = 0
    private var lastHeight = 0
    private var started = false
    private var scratch: AvsFrame? = null

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (lastWidth != frame.width || lastHeight != frame.height) {
            lastWidth = frame.width
            lastHeight = frame.height
            maxDistance = sqrt((frame.width.toDouble() * frame.width + frame.height.toDouble() * frame.height) / 4.0)
            table = IntArray(((maxDistance + HEADROOM).toInt()).coerceAtLeast(GUARD + 1))
        }
        if (!started) {
            started = true
            init?.run()
        }
        b.value = if (state.beat) 1.0 else 0.0
        perFrame?.run()
        if (state.beat) onBeat?.run()

        if (perPoint != null) {
            for (radius in 0 until table.size - GUARD) {
                d.value = radius / (maxDistance - 1)
                perPoint.run()
                table[radius] = (d.value * TABLE_SCALE * maxDistance / (radius + 1)).toInt()
            }
            for (radius in table.size - GUARD until table.size) {
                table[radius] = table[radius - 1]
            }
        } else {
            // AVS zeroes the table without valid point code: every pixel then
            // reads the center
            table.fill(0)
        }

        val source = scratchFor(frame)
        source.copyFrom(frame)
        applyTable(source, frame, table, config.fiftyFifty)
    }

    private fun scratchFor(frame: AvsFrame): AvsFrame {
        val existing = scratch
        if (existing != null && existing.sameSizeAs(frame)) return existing
        return AvsFrame(frame.width, frame.height).also { scratch = it }
    }

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    companion object {
        private const val SECTIONS = 4

        /** AVS sizes the table `max_d + 32.9` and copies the last 32 entries. */
        private const val HEADROOM = 32.9
        private const val GUARD = 32
        private const val TABLE_SCALE = 256.0

        fun read(
            body: ByteArray,
            eel: Eel,
        ): DynamicDistanceModifierRenderer? {
            val config = readConfig(body) ?: return null
            return DynamicDistanceModifierRenderer(config, eel)
        }

        /**
         * The parse alone, so a JVM test can call it without the evaluator's
         * native library.
         *
         * The code is stored as per point, per frame, on beat, init, followed
         * by a blend (1 means fifty-fifty) and a bilinear flag.
         */
        fun readConfig(body: ByteArray): DdmConfig? {
            if (body.isEmpty()) return null
            val current = body[0].toInt() == 1
            val reader = CodeSectionReader(body, if (current) 1 else 0, current)
            val code = List(SECTIONS) { reader.next() }
            val rest = reader.rest()
            val blend = rest.int32() == 1
            // bilinear follows and is not read
            return if (reader.ok && rest.ok) {
                DdmConfig(
                    init = code[3],
                    perFrame = code[1],
                    onBeat = code[2],
                    perPoint = code[0],
                    fiftyFifty = blend,
                )
            } else {
                null
            }
        }

        /**
         * The radial pixel loop, transcribed: each pixel's radius (by [isqrt]
         * of an incrementally maintained square) picks a multiplier, which
         * scales the pixel's offset from the center.
         */
        internal fun applyTable(
            source: AvsFrame,
            destination: AvsFrame,
            table: IntArray,
            fiftyFifty: Boolean,
        ) {
            val w = destination.width
            val h = destination.height
            val w2 = w / 2
            val h2 = h / 2
            var dest = 0
            for (y in 0 until h) {
                val ty = y - h2
                var squared = w2 * w2 + w2 + ty * ty + 256
                var squaredStep = -2 * w2
                var xsc = -w2
                for (x in 0 until w) {
                    // clamped where AVS relies on its 32-entry headroom; only
                    // a table too small for the frame reaches the clamp
                    val qd = table[isqrt(squared).coerceAtMost(table.size - 1)]
                    squared += squaredStep
                    squaredStep += 2
                    val ow = (w2 + ((qd * xsc + 128) shr 8)).coerceIn(0, w - 1)
                    val oh = (h2 + ((qd * ty + 128) shr 8)).coerceIn(0, h - 1)
                    xsc++
                    val pixel = source.pixels[ow + oh * w]
                    destination.pixels[dest] =
                        if (fiftyFifty) {
                            AvsBlend.pixel(AvsBlendMode.FIFTY_FIFTY, pixel, source.pixels[dest])
                        } else {
                            pixel
                        }
                    dest++
                }
            }
        }

        /**
         * AVS's table-driven integer square root, transcribed with its table.
         * It is approximate: nearby radii can land in the same bucket.
         */
        internal fun isqrt(n: Int): Int =
            if (n >= 0x10000) {
                if (n >= 0x1000000) {
                    when {
                        n >= 0x40000000 -> SQ_TABLE[n ushr 24] shl 8
                        n >= 0x10000000 -> SQ_TABLE[n shr 22] shl 7
                        n >= 0x4000000 -> SQ_TABLE[n shr 20] shl 6
                        else -> SQ_TABLE[n shr 18] shl 5
                    }
                } else if (n >= 0x100000) {
                    if (n >= 0x400000) SQ_TABLE[n shr 16] shl 4 else SQ_TABLE[n shr 14] shl 3
                } else if (n >= 0x40000) {
                    SQ_TABLE[n shr 12] shl 2
                } else {
                    SQ_TABLE[n shr 10] shl 1
                }
            } else if (n >= 0x100) {
                when {
                    n >= 0x4000 -> SQ_TABLE[n shr 8]
                    n >= 0x1000 -> SQ_TABLE[n shr 6] shr 1
                    n >= 0x400 -> SQ_TABLE[n shr 4] shr 2
                    else -> SQ_TABLE[n shr 2] shr 3
                }
            } else if (n >= 0x10) {
                if (n >= 0x40) SQ_TABLE[n] shr 4 else SQ_TABLE[n shl 2] shl 5
            } else if (n >= 0x4) {
                SQ_TABLE[n shr 4] shl 6
            } else {
                SQ_TABLE[n shr 6] shl 7
            }

        // 256 entries, e_dynamicdistancemodifier.cpp's sq_table verbatim
        private val SQ_TABLE =
            intArrayOf(
                0,
                16,
                22,
                27,
                32,
                35,
                39,
                42,
                45,
                48,
                50,
                53,
                55,
                57,
                59,
                61,
                64,
                65,
                67,
                69,
                71,
                73,
                75,
                76,
                78,
                80,
                81,
                83,
                84,
                86,
                87,
                89,
                90,
                91,
                93,
                94,
                96,
                97,
                98,
                99,
                101,
                102,
                103,
                104,
                106,
                107,
                108,
                109,
                110,
                112,
                113,
                114,
                115,
                116,
                117,
                118,
                119,
                120,
                121,
                122,
                123,
                124,
                125,
                126,
                128,
                128,
                129,
                130,
                131,
                132,
                133,
                134,
                135,
                136,
                137,
                138,
                139,
                140,
                141,
                142,
                143,
                144,
                144,
                145,
                146,
                147,
                148,
                149,
                150,
                150,
                151,
                152,
                153,
                154,
                155,
                155,
                156,
                157,
                158,
                159,
                160,
                160,
                161,
                162,
                163,
                163,
                164,
                165,
                166,
                167,
                167,
                168,
                169,
                170,
                170,
                171,
                172,
                173,
                173,
                174,
                175,
                176,
                176,
                177,
                178,
                178,
                179,
                180,
                181,
                181,
                182,
                183,
                183,
                184,
                185,
                185,
                186,
                187,
                187,
                188,
                189,
                189,
                190,
                191,
                192,
                192,
                193,
                193,
                194,
                195,
                195,
                196,
                197,
                197,
                198,
                199,
                199,
                200,
                201,
                201,
                202,
                203,
                203,
                204,
                204,
                205,
                206,
                206,
                207,
                208,
                208,
                209,
                209,
                210,
                211,
                211,
                212,
                212,
                213,
                214,
                214,
                215,
                215,
                216,
                217,
                217,
                218,
                218,
                219,
                219,
                220,
                221,
                221,
                222,
                222,
                223,
                224,
                224,
                225,
                225,
                226,
                226,
                227,
                227,
                228,
                229,
                229,
                230,
                230,
                231,
                231,
                232,
                232,
                233,
                234,
                234,
                235,
                235,
                236,
                236,
                237,
                237,
                238,
                238,
                239,
                240,
                240,
                241,
                241,
                242,
                242,
                243,
                243,
                244,
                244,
                245,
                245,
                246,
                246,
                247,
                247,
                248,
                248,
                249,
                249,
                250,
                250,
                251,
                251,
                252,
                252,
                253,
                253,
                254,
                254,
                255,
            )
    }
}

/** A Dynamic Distance Modifier's settings: four code sections and a blend. */
internal data class DdmConfig(
    val init: String = "",
    val perFrame: String = "",
    val onBeat: String = "",
    val perPoint: String = "",
    val fiftyFifty: Boolean = false,
)
