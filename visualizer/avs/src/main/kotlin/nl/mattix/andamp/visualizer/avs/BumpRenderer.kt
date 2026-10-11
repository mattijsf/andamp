// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.abs

/**
 * Lights the frame as if it were a relief: brightness is height, and a light
 * the preset's code moves around shines across it.
 *
 * Transcribed from vis_avs `r_bump.cpp` (BSD; see NOTICE.md). The code sets
 * `x` and `y`, the light's place as a fraction of the frame, and may scale the
 * depth through `bi`. A pixel is lit by how closely the slope of the relief at
 * that pixel points at the light, on each axis: `127 - |slope - distance|`,
 * and the product of the two, scaled by the depth, is added to the pixel's own
 * color. A pixel facing away keeps its color. The relief is the frame itself
 * or one of the global buffers.
 *
 * The outermost ring of pixels comes out black, and so does a pixel of a
 * frame that is its own relief when the four around it are black.
 */
internal class BumpRenderer(
    private val settings: Settings,
    perFrame: String,
    onBeat: String,
    init: String,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    internal data class Settings(
        val enabled: Boolean = true,
        val onBeat: Boolean = false,
        val beatFrames: Int = 15,
        /** Percent. */
        val depth: Int = 30,
        val beatDepth: Int = 100,
        val blend: AvsBlendMode = AvsBlendMode.REPLACE,
        val showLight: Boolean = false,
        val invert: Boolean = false,
        /** The oldest presets give the light's place in percent, not as a fraction. */
        val percentCoordinates: Boolean = true,
        /** 0 is the frame itself; 1 to 8 a global buffer. */
        val buffer: Int = 0,
    )

    private val lightX = eel.variable("x")
    private val lightY = eel.variable("y")
    private val isBeat = eel.variable("isbeat")
    private val isLongBeat = eel.variable("islbeat")
    private val intensity = eel.variable("bi").also { it.value = 1.0 }

    private val frameCode = compile(perFrame)
    private val beatCode = compile(onBeat)
    private val initCode = compile(init)

    override val errors: List<String> =
        buildList {
            if (perFrame.isNotBlank() && frameCode == null) add("per frame")
            if (onBeat.isNotBlank() && beatCode == null) add("on beat")
            if (init.isNotBlank() && initCode == null) add("init")
        }

    private var started = false
    private var depthNow = settings.depth
    private var beatFramesLeft = 0
    private var incoming = IntArray(0)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!settings.enabled) return
        val relief = if (settings.buffer == 0) null else state.buffers.peek(settings.buffer - 1)?.pixels
        if (settings.buffer != 0 && relief == null) return

        runCode(state.beat)

        val w = frame.width
        val h = frame.height
        val scale = if (settings.percentCoordinates) PERCENT else 1.0
        val lightAtX = (lightX.value / scale * w).toInt().coerceIn(0, w)
        val lightAtY = (lightY.value / scale * h).toInt().coerceIn(0, h)
        intensity.value = intensity.value.coerceIn(0.0, 1.0)
        depthNow = (depthNow * intensity.value).toInt()

        if (incoming.size != w * h) incoming = IntArray(w * h)
        val out = frame.pixels
        out.copyInto(incoming)
        out.fill(AvsFrame.OPAQUE)
        if (settings.showLight) (lightAtX + lightAtY * w).takeIf { it in out.indices }?.let { out[it] = WHITE }
        light(out, incoming, relief ?: incoming, relief == null, w, h, lightAtX, lightAtY)
        settle()
    }

    /** The preset's code for this frame, and the depth a beat sets. */
    private fun runCode(beat: Boolean) {
        if (!started) {
            started = true
            initCode?.run()
        }
        frameCode?.run()
        if (beat) beatCode?.run()
        // the code sees these a frame late, as in the original
        isBeat.value = if (beat) -1.0 else 1.0
        isLongBeat.value = if (beatFramesLeft != 0) -1.0 else 1.0
        if (settings.onBeat && beat) {
            depthNow = settings.beatDepth
            beatFramesLeft = settings.beatFrames
        } else if (beatFramesLeft == 0) {
            depthNow = settings.depth
        }
    }

    /** After a beat, the depth walks back to its resting value over the beat's frames. */
    private fun settle() {
        if (beatFramesLeft == 0) return
        beatFramesLeft--
        if (beatFramesLeft != 0) {
            val step = abs(settings.depth - settings.beatDepth) / settings.beatFrames.coerceAtLeast(1)
            depthNow += step * if (settings.beatDepth > settings.depth) -1 else 1
        }
    }

    @Suppress("LongParameterList") // one pass over the frame: where it writes, what it reads, and where the light is
    private fun light(
        out: IntArray,
        colours: IntArray,
        relief: IntArray,
        reliefIsFrame: Boolean,
        w: Int,
        h: Int,
        lightAtX: Int,
        lightAtY: Int,
    ) {
        val depth = (depthNow shl DEPTH_SHIFT) / PERCENT_INT
        for (y in 1 until h - 1) {
            val fromLightY = y - lightAtY
            for (x in 1 until w - 1) {
                val at = y * w + x
                val left = relief[at - 1]
                val right = relief[at + 1]
                val above = relief[at - w]
                val below = relief[at + w]
                if (reliefIsFrame && (left or right or above or below) and RGB == 0) continue
                val across = FACING - abs(heightOf(right) - heightOf(left) - (x - lightAtX))
                val down = FACING - abs(heightOf(below) - heightOf(above) - fromLightY)
                val own = colours[at]
                val lit = if (across <= 0 || down <= 0) raised(own, 0) else raised(own, (across * down * depth) shr LIGHT_SHIFT)
                out[at] =
                    when (settings.blend) {
                        AvsBlendMode.ADDITIVE -> AvsBlend.pixel(AvsBlendMode.ADDITIVE, own, lit, FULL)
                        AvsBlendMode.FIFTY_FIFTY -> AvsBlend.pixel(AvsBlendMode.FIFTY_FIFTY, own, lit, FULL)
                        else -> lit
                    }
            }
        }
    }

    /** A pixel's height: its brightest channel, or the reverse of it. */
    private fun heightOf(pixel: Int): Int {
        val brightest = maxOf(redOf(pixel), greenOf(pixel), blueOf(pixel))
        return if (settings.invert) FULL - brightest else brightest
    }

    /** [pixel] with [light] added to every channel, each stopping at 254. */
    private fun raised(
        pixel: Int,
        light: Int,
    ) = AvsFrame.OPAQUE or
        (minOf(redOf(pixel) + light, BRIGHTEST) shl RED_SHIFT) or
        (minOf(greenOf(pixel) + light, BRIGHTEST) shl GREEN_SHIFT) or
        minOf(blueOf(pixel) + light, BRIGHTEST)

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    companion object {
        private const val PERCENT = 100.0
        private const val PERCENT_INT = 100
        private const val DEPTH_SHIFT = 8
        private const val LIGHT_SHIFT = 14
        private const val FACING = 127
        private const val FULL = 255
        private const val BRIGHTEST = 254
        private const val RGB = 0xFFFFFF
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8

        fun read(
            body: ByteArray,
            eel: Eel,
        ): BumpRenderer {
            val parsed = parse(body)
            return BumpRenderer(parsed.settings, parsed.perFrame, parsed.onBeat, parsed.init, eel)
        }

        /** The body without the evaluator, so a JVM test can call it. */
        internal fun parse(body: ByteArray): Parsed {
            val reader = BodyReader(body)
            val start = Settings()
            val enabled = reader.int32Or(1) != 0
            val onBeat = reader.int32Or(0) != 0
            val beatFrames = reader.int32Or(start.beatFrames)
            val depth = reader.int32Or(start.depth)
            val beatDepth = reader.int32Or(start.beatDepth)
            val additive = reader.int32Or(0) != 0
            val average = reader.int32Or(0) != 0
            val perFrame = reader.string()
            val onBeatCode = reader.string()
            val init = reader.string()
            val showLight = reader.int32Or(0) != 0
            val invert = reader.int32Or(0) != 0
            // absent in the oldest presets, which place the light in percent
            val percent = if (reader.has(INT_SIZE)) reader.int32() != 0 else true
            val buffer = reader.int32Or(0)
            val blend =
                when {
                    additive -> AvsBlendMode.ADDITIVE
                    average -> AvsBlendMode.FIFTY_FIFTY
                    else -> AvsBlendMode.REPLACE
                }
            return Parsed(
                Settings(enabled, onBeat, beatFrames, depth, beatDepth, blend, showLight, invert, percent, buffer),
                perFrame,
                onBeatCode,
                init,
            )
        }

        private const val INT_SIZE = 4
    }

    internal class Parsed(
        val settings: Settings,
        val perFrame: String,
        val onBeat: String,
        val init: String,
    )
}
