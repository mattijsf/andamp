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

    /** A float, or [default] when the body ends before it. */
    fun floatOr(default: Float): Float = if (has(INT_SIZE)) float() else default

    /** Whether [count] more bytes are left to read. */
    fun has(count: Int) = bytes.size - at >= count

    /**
     * The next int32, or [default] when the body ends before it. AVS reads
     * every setting this way: an older build wrote fewer of them, and a
     * setting that is not there keeps the value a new component starts with.
     */
    fun int32Or(default: Int): Int = if (has(INT_SIZE)) int32() else default

    /**
     * A length and that many bytes of text, as vis_avs `load_string` reads
     * it; empty when the length is not positive or runs past the body.
     */
    fun string(): String {
        if (!has(INT_SIZE)) return ""
        val length = int32()
        if (length <= 0 || !has(length)) return ""
        val end = (at until at + length).firstOrNull { bytes[it].toInt() == 0 } ?: (at + length)
        return String(bytes, at, end - at, Charsets.ISO_8859_1).also { at += length }
    }

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

        fun read(body: ByteArray) = InvertRenderer(enabled = BodyReader(body).int32Or(1) != 0)
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

        fun read(body: ByteArray): FadeOutRenderer {
            val reader = BodyReader(body)
            val speed = reader.int32Or(DEFAULT_SPEED).coerceIn(0, MAX_SPEED)
            val colour = AvsFrame.fromConfig(reader.int32Or(0))
            return FadeOutRenderer(speed, colour)
        }

        private const val DEFAULT_SPEED = 16
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

        fun read(body: ByteArray): BlurRenderer {
            val reader = BodyReader(body)
            // a new Blur is medium, and so is any level the list does not name
            val level = LEVELS.getOrNull(reader.int32Or(1)) ?: AvsBlurLevel.MEDIUM
            // old bodies end before the round field
            val roundUp = reader.int32Or(0) != 0
            return BlurRenderer(level, roundUp)
        }
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
        fun read(body: ByteArray): BufferSaveRenderer {
            val reader = BodyReader(body)
            val action = AvsBufferAction.entries.getOrNull(reader.int32Or(0)) ?: AvsBufferAction.SAVE
            // the file counts buffers from zero, and r_stack.cpp clamps the
            // number into the eight there are
            val buffer = reader.int32Or(0).coerceIn(0, LAST_BUFFER)
            val blend = AvsBlendMode.buffered(reader.int32Or(0))
            val adjust = reader.int32Or(DEFAULT_ADJUST)
            return BufferSaveRenderer(action, buffer, blend, adjust)
        }

        private const val LAST_BUFFER = 7
        private const val DEFAULT_ADJUST = 128
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
        fun read(body: ByteArray): SetRenderModeRenderer {
            val mode = BodyReader(body).int32Or(DEFAULT_MODE)
            return SetRenderModeRenderer(
                enabled = (mode ushr FLAGS_SHIFT) and ENABLED_BIT != 0,
                blend = AvsBlendMode.rendering(mode and BYTE),
                adjust = (mode ushr ADJUST_SHIFT) and BYTE,
                lineSize = (mode ushr LINE_SHIFT) and BYTE,
            )
        }

        /** What a new Set Render Mode holds: enabled, replace, lines one pixel wide. */
        private const val DEFAULT_MODE = 0x80010000.toInt()
        private const val BYTE = 0xFF
        private const val ADJUST_SHIFT = 8
        private const val LINE_SHIFT = 16
        private const val FLAGS_SHIFT = 24
    }
}
