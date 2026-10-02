// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interferences: the frame layered over itself N times around a circle, each
 * copy scaled by an alpha, summed and clamped. Transcribed from
 * `e_interferences.cpp` (vis_avs, BSD; see NOTICE.md), including the on-beat
 * lerp: a beat restarts a sine fade that carries distance, alpha and rotation
 * from their base values to their on-beat values and back.
 *
 * With `separate_rgb` and a multiple of three layers, each layer feeds only one
 * color channel, so three layers give a red/green/blue split.
 */
internal class InterferencesRenderer(
    private val config: InterferencesConfig,
) : AvsComponentRenderer {
    private var currentRotation = config.initRotation
    private var fadeout = Math.PI
    private var scratch: AvsFrame? = null

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!config.enabled || config.layers == 0) return
        if (config.onBeat && state.beat && fadeout >= Math.PI) fadeout = 0.0
        val s = sin(fadeout)

        val rotationStep = lerp(config.rotation, config.onBeatRotation, s)
        val alphaNow = lerp(config.alpha, config.onBeatAlpha, s)
        val distanceNow = lerp(config.distance, config.onBeatDistance, s)

        val xPoints = IntArray(config.layers)
        val yPoints = IntArray(config.layers)
        var angle = currentRotation.toDouble() / FULL * 2.0 * PI
        val angleStep = 2.0 * PI / config.layers
        for (i in 0 until config.layers) {
            xPoints[i] = (cos(angle) * distanceNow).toInt()
            yPoints[i] = (sin(angle) * distanceNow).toInt()
            angle += angleStep
        }

        val out = scratchFor(frame)
        val splitChannels = config.separateRgb && config.layers % CHANNELS_SPLIT == 0
        layerSum(frame, out, xPoints, yPoints, alphaNow, splitChannels)

        currentRotation += rotationStep
        if (currentRotation > FULL) currentRotation -= FULL
        if (currentRotation < -FULL) currentRotation += FULL
        fadeout = (fadeout + config.onBeatSpeed).coerceAtMost(Math.PI)
        if (fadeout < -Math.PI) fadeout = Math.PI

        if (config.blend == AvsBlendMode.REPLACE) frame.copyFrom(out) else AvsBlend.blend(config.blend, out, frame)
    }

    @Suppress("LongParameterList") // the hot loop's inputs, precomputed once per frame
    private fun layerSum(
        frame: AvsFrame,
        out: AvsFrame,
        xPoints: IntArray,
        yPoints: IntArray,
        alphaNow: Int,
        splitChannels: Boolean,
    ) {
        val w = frame.width
        val h = frame.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[x, y] = sumAt(frame, x, y, xPoints, yPoints, alphaNow, splitChannels)
            }
        }
    }

    @Suppress("LongParameterList") // the hot loop's inputs, precomputed once per frame
    private fun sumAt(
        frame: AvsFrame,
        x: Int,
        y: Int,
        xPoints: IntArray,
        yPoints: IntArray,
        alphaNow: Int,
        splitChannels: Boolean,
    ): Int {
        var r = 0
        var g = 0
        var b = 0
        for (i in 0 until config.layers) {
            val sx = x - xPoints[i]
            val sy = y - yPoints[i]
            if (sx !in 0 until frame.width || sy !in 0 until frame.height) continue
            val pixel = frame[sx, sy]
            if (splitChannels) {
                // layer i feeds channel i%3, like the original's unrolled
                // three- and six-layer special cases
                when (i % CHANNELS_SPLIT) {
                    0 -> r += scaled(redOf(pixel), alphaNow)
                    1 -> g += scaled(greenOf(pixel), alphaNow)
                    else -> b += scaled(blueOf(pixel), alphaNow)
                }
            } else {
                r += scaled(redOf(pixel), alphaNow)
                g += scaled(greenOf(pixel), alphaNow)
                b += scaled(blueOf(pixel), alphaNow)
            }
        }
        return pixelOf(r, g, b)
    }

    private fun scaled(
        channel: Int,
        by: Int,
    ) = channel * by / FULL

    private fun lerp(
        from: Int,
        to: Int,
        s: Double,
    ) = (from + (to - from) * s).toInt()

    private fun scratchFor(frame: AvsFrame): AvsFrame {
        val existing = scratch
        if (existing != null && existing.sameSizeAs(frame)) return existing
        return AvsFrame(frame.width, frame.height).also { scratch = it }
    }

    companion object {
        private const val FULL = 255
        private const val CHANNELS_SPLIT = 3

        fun read(body: ByteArray): InterferencesRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val layers = reader.int32().coerceIn(0, MAX_LAYERS)
            val initRotation = reader.int32()
            val distance = reader.int32()
            val alpha = reader.int32()
            val rotation = reader.int32()
            // two ints, first wins: additive, else fifty-fifty, else replace
            val additive = reader.int32() != 0
            val fifty = reader.int32() != 0
            val blend =
                when {
                    additive -> AvsBlendMode.ADDITIVE
                    fifty -> AvsBlendMode.FIFTY_FIFTY
                    else -> AvsBlendMode.REPLACE
                }
            val config =
                InterferencesConfig(
                    enabled = enabled,
                    layers = layers,
                    initRotation = initRotation,
                    distance = distance,
                    alpha = alpha,
                    rotation = rotation,
                    blend = blend,
                    onBeatDistance = reader.int32(),
                    onBeatAlpha = reader.int32(),
                    onBeatRotation = reader.int32(),
                    separateRgb = reader.int32() != 0,
                    onBeat = reader.int32() != 0,
                    onBeatSpeed = reader.float(),
                )
            return if (reader.ok) InterferencesRenderer(config) else null
        }

        /** INTERFERENCES_MAX_POINTS in the original. */
        private const val MAX_LAYERS = 8
    }
}

/** The body's fields, in the order the file stores them; the file's two blend ints are one [blend]. */
internal data class InterferencesConfig(
    val enabled: Boolean,
    val layers: Int,
    val initRotation: Int,
    val distance: Int,
    val alpha: Int,
    val rotation: Int,
    val blend: AvsBlendMode,
    val onBeatDistance: Int,
    val onBeatAlpha: Int,
    val onBeatRotation: Int,
    val separateRgb: Boolean,
    val onBeat: Boolean,
    val onBeatSpeed: Float,
)
