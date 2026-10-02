// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

// The body reader, and six small components: Invert, FadeOut, Blur, Buffer Save,
// Comment and Set Render Mode.
//
// Every field layout is transcribed from grandchild/AVS-File-Decoder (MIT); see
// NOTICE.md.

/** Reads a component body a field at a time. A read past the end answers 0 and clears [ok]. */
internal class BodyReader(
    private val bytes: ByteArray,
) {
    var ok = true
        private set

    private var at = 0

    fun int32(): Int {
        if (at + INT_SIZE > bytes.size) {
            ok = false
            return 0
        }
        val value =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
        at += INT_SIZE
        return value
    }

    /** A 4-byte IEEE float. */
    fun float(): Float = Float.fromBits(int32())

    fun byte(): Int {
        if (at >= bytes.size) {
            ok = false
            return 0
        }
        return bytes[at++].toInt() and 0xFF
    }

    private companion object {
        const val INT_SIZE = 4
    }
}

/** Every channel of every pixel, flipped. */
internal class InvertRenderer(
    private val enabled: Boolean,
) : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        for (i in frame.pixels.indices) {
            frame.pixels[i] = frame.pixels[i] xor CHANNELS
        }
    }

    companion object {
        private const val CHANNELS = 0x00FFFFFF

        fun read(body: ByteArray): InvertRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            return if (reader.ok) InvertRenderer(enabled) else null
        }
    }
}

/**
 * Every channel walks towards a color, [speed] steps a frame. A channel within
 * one step of the target lands on it.
 */
internal class FadeOutRenderer(
    private val speed: Int,
    private val target: Int,
) : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (speed <= 0) return
        for (i in frame.pixels.indices) {
            frame.pixels[i] = fade(frame.pixels[i])
        }
    }

    // unrolled: this runs per pixel per frame, and a loop over an
    // intArrayOf(16, 8, 0) is an allocation per pixel
    private fun fade(pixel: Int): Int =
        AvsFrame.OPAQUE or
            (step(redOf(pixel), redOf(target)) shl 16) or
            (step(greenOf(pixel), greenOf(target)) shl 8) or
            step(blueOf(pixel), blueOf(target))

    private fun step(
        from: Int,
        to: Int,
    ): Int =
        when {
            from > to -> maxOf(to, from - speed)
            from < to -> minOf(to, from + speed)
            else -> from
        }

    companion object {
        /** The top of AVS's own range. */
        private const val MAX_SPEED = 92

        fun read(body: ByteArray): FadeOutRenderer? {
            val reader = BodyReader(body)
            val speed = reader.int32().coerceIn(0, MAX_SPEED)
            val colour = AvsFrame.fromConfig(reader.int32())
            return if (reader.ok) FadeOutRenderer(speed, colour) else null
        }
    }
}

/** How hard a [BlurRenderer] blurs. */
internal enum class AvsBlurLevel { NONE, LIGHT, MEDIUM, HEAVY }

/**
 * AVS's blur, transcribed from `e_blur.cpp` (vis_avs, BSD; see NOTICE.md).
 *
 * Three fixed cross-shaped kernels: light keeps three quarters of the pixel
 * and takes a sixteenth from each neighbor, medium keeps half and takes an
 * eighth, heavy drops the pixel for a quarter of each neighbor. Round-up adds
 * a constant per channel (the `*_BIAS` values), which keeps a frame that is
 * blurred every frame from dimming to black. The round field is the body's
 * second int, absent in old files.
 *
 * Edges: the original has hand-written weights for every border case. Here a
 * missing neighbor reads the center pixel, which differs from the original
 * only in the outermost pixel ring.
 */
internal class BlurRenderer(
    private val level: AvsBlurLevel,
    private val roundUp: Boolean = false,
) : AvsComponentRenderer {
    private var scratch: IntArray = IntArray(0)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (level == AvsBlurLevel.NONE) return
        if (scratch.size != frame.pixels.size) scratch = IntArray(frame.pixels.size)
        val width = frame.width
        val height = frame.height
        val pixels = frame.pixels
        val bias =
            if (!roundUp) {
                0
            } else {
                when (level) {
                    AvsBlurLevel.LIGHT -> LIGHT_BIAS
                    AvsBlurLevel.MEDIUM -> MEDIUM_BIAS
                    else -> HEAVY_BIAS
                }
            }

        for (y in 0 until height) {
            val row = y * width
            val up = if (y > 0) row - width else row
            val down = if (y < height - 1) row + width else row
            for (x in 0 until width) {
                val c = pixels[row + x]
                val l = pixels[row + if (x > 0) x - 1 else x]
                val r = pixels[row + if (x < width - 1) x + 1 else x]
                val u = pixels[up + x]
                val d = pixels[down + x]
                // per-byte parallel shifts, like the original's DIV_n macros:
                // alpha is masked out and set again
                val mixed =
                    when (level) {
                        AvsBlurLevel.LIGHT -> div2(c) + div4(c) + div16(l) + div16(r) + div16(u) + div16(d)
                        AvsBlurLevel.MEDIUM -> div2(c) + div8(l) + div8(r) + div8(u) + div8(d)
                        else -> div4(l) + div4(r) + div4(u) + div4(d)
                    }
                scratch[row + x] = AvsFrame.OPAQUE or ((mixed + bias) and CHANNELS)
            }
        }
        scratch.copyInto(pixels)
    }

    companion object {
        private const val CHANNELS = 0x00FFFFFF

        private fun div2(p: Int) = (p shr 1) and 0x7F7F7F

        private fun div4(p: Int) = (p shr 2) and 0x3F3F3F

        private fun div8(p: Int) = (p shr 3) and 0x1F1F1F

        private fun div16(p: Int) = (p shr 4) and 0x0F0F0F

        /** The interior round-up constants from e_blur.cpp: +5, +4 and +2 per channel. */
        private const val LIGHT_BIAS = 0x050505
        private const val MEDIUM_BIAS = 0x040404
        private const val HEAVY_BIAS = 0x020202

        /**
         * The file's order is not the enum's: 0 disables, 1 medium, 2 light,
         * 3 heavy.
         */
        private val LEVELS = listOf(AvsBlurLevel.NONE, AvsBlurLevel.MEDIUM, AvsBlurLevel.LIGHT, AvsBlurLevel.HEAVY)

        fun read(body: ByteArray): BlurRenderer? {
            val reader = BodyReader(body)
            val level = LEVELS.getOrNull(reader.int32()) ?: AvsBlurLevel.NONE
            if (!reader.ok) return null
            // old bodies end before the round field
            val roundUp = body.size >= ROUNDED_BODY && reader.int32() == 1
            return BlurRenderer(level, roundUp)
        }

        private const val ROUNDED_BODY = 8
    }
}

/** What a [BufferSaveRenderer] does with its buffer this frame. */
internal enum class AvsBufferAction { SAVE, RESTORE, ALTERNATE_SAVE_RESTORE, ALTERNATE_RESTORE_SAVE }

/**
 * Puts the frame in a buffer, or a buffer over the frame. The two alternating
 * modes flip on every frame.
 */
internal class BufferSaveRenderer(
    private val action: AvsBufferAction,
    private val buffer: Int,
    private val blend: AvsBlendMode,
    private val adjust: Int,
) : AvsComponentRenderer {
    private var flipped = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        val saving =
            when (action) {
                AvsBufferAction.SAVE -> true
                AvsBufferAction.RESTORE -> false
                AvsBufferAction.ALTERNATE_SAVE_RESTORE -> !flipped
                AvsBufferAction.ALTERNATE_RESTORE_SAVE -> flipped
            }
        flipped = !flipped
        // a restore never creates the buffer: reading one nothing wrote is a
        // no-op, which is e_buffersave's get_buffer(..., action != RESTORE)
        val target = (if (saving) state.buffers[buffer] else state.buffers.peek(buffer)) ?: return
        if (!target.sameSizeAs(frame)) return
        // the blend applies in both directions: a save blends the frame into
        // the buffer under the same mode a restore uses coming back
        if (saving) AvsBlend.blend(blend, frame, target, adjust) else AvsBlend.blend(blend, target, frame, adjust)
    }

    companion object {
        fun read(body: ByteArray): BufferSaveRenderer? {
            val reader = BodyReader(body)
            val action = AvsBufferAction.entries.getOrNull(reader.int32()) ?: AvsBufferAction.SAVE
            // one-based in the file: e_buffersave passes config.buffer - 1 to
            // get_buffer, and so do the Effect List's buffer fields
            val buffer = reader.int32() - 1
            val blend = AvsBlendMode.buffered(reader.int32())
            val adjust = reader.int32()
            return if (reader.ok) BufferSaveRenderer(action, buffer, blend, adjust) else null
        }
    }
}

/**
 * A note the preset's author left. It draws nothing; it has a renderer so that
 * a preset containing one is not reported as missing a component.
 */
internal class CommentRenderer : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) = Unit
}

/**
 * Draws nothing, and changes how everything after it draws: the blend a Render
 * component puts its marks down with, and the line width it uses, until
 * another one changes them.
 */
internal class SetRenderModeRenderer(
    private val enabled: Boolean,
    private val blend: AvsBlendMode,
    private val adjust: Int,
    private val lineSize: Int,
) : AvsComponentRenderer {
    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (enabled) state.setRenderMode(blend, adjust, lineSize)
    }

    companion object {
        private const val ENABLED_BIT = 0x80

        /**
         * One int32, a byte a field: blend, adjust, line size, and the enabled
         * flag as the top bit of the fourth byte, which is bit 31 of the whole
         * int (`r_linemode.cpp`'s 0x80000000).
         */
        fun read(body: ByteArray): SetRenderModeRenderer? {
            val reader = BodyReader(body)
            val blendByte = reader.byte()
            val adjust = reader.byte()
            val lineSize = reader.byte()
            val flags = reader.byte()
            return if (reader.ok) {
                SetRenderModeRenderer(
                    enabled = flags and ENABLED_BIT != 0,
                    blend = AvsBlendMode.rendering(blendByte),
                    adjust = adjust,
                    lineSize = lineSize,
                )
            } else {
                null
            }
        }
    }
}
