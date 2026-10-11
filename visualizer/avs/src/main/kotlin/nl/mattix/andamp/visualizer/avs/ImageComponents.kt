// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

// Picture, Picture II and Texer: a picture from the preset's pack put on the
// frame. Transcribed from the vis_avs source (`r_picture.cpp`,
// `e_picture2.cpp`, `e_texer.cpp`; BSD 3-clause, see NOTICE.md).

/** A file name stored in a fixed field: the text up to the first null. */
private fun fixedName(
    body: ByteArray,
    from: Int,
    length: Int,
): String {
    if (from >= body.size) return ""
    val until = minOf(from + length, body.size)
    val end = (from until until).firstOrNull { body[it].toInt() == 0 } ?: until
    return String(body, from, end - from, Charsets.ISO_8859_1)
}

private const val PATH_SIZE = 260

/**
 * A picture stretched over the whole frame, or kept in shape along one axis
 * and centered on black.
 *
 * Transcribed from vis_avs `r_picture.cpp`. It is added to the frame, mixed
 * half and half, or replaces it. "Adaptive" mixes half and half and switches
 * to adding on a beat, for as many frames after it as the preset says.
 */
internal class PictureRenderer(
    private val settings: Settings,
) : AvsComponentRenderer,
    AvsPictured {
    internal data class Settings(
        val enabled: Boolean = true,
        val additive: Boolean = false,
        val average: Boolean = true,
        val adaptive: Boolean = false,
        val persist: Int = 0,
        val name: String = "",
        val keepRatio: Boolean = false,
        /** With [keepRatio]: false fits the width, true the height. */
        val fitHeight: Boolean = false,
    )

    private var image: AvsImage? = null
    private var stretched = IntArray(0)
    private var stretchedFor = 0L
    private var beatFramesLeft = 0

    override fun attach(images: AvsImages): String? {
        if (settings.name.isEmpty()) return null
        image = images.load(settings.name)
        return if (image == null) settings.name else null
    }

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        val picture = image ?: return
        if (!settings.enabled) return
        val key = frame.width.toLong() shl Int.SIZE_BITS or frame.height.toLong()
        if (stretchedFor != key) {
            stretched = stretch(picture, frame.width, frame.height)
            stretchedFor = key
        }
        if (state.beat) {
            beatFramesLeft = settings.persist
        } else if (beatFramesLeft > 0) {
            beatFramesLeft--
        }
        val pixels = frame.pixels
        val adding = settings.additive || (settings.adaptive && (state.beat || beatFramesLeft > 0))
        when {
            adding -> {
                for (i in pixels.indices) pixels[i] = AvsBlend.pixel(AvsBlendMode.ADDITIVE, stretched[i], pixels[i])
            }

            settings.average || settings.adaptive -> {
                for (i in pixels.indices) pixels[i] = AvsBlend.pixel(AvsBlendMode.FIFTY_FIFTY, stretched[i], pixels[i])
            }

            else -> {
                stretched.copyInto(pixels)
            }
        }
    }

    private fun stretch(
        picture: AvsImage,
        w: Int,
        h: Int,
    ): IntArray {
        var across = w
        var down = h
        var left = 0
        var top = 0
        if (settings.keepRatio) {
            if (settings.fitHeight) {
                across = (picture.width * h / picture.height).coerceAtLeast(1)
                left = w / 2 - across / 2
            } else {
                down = (picture.height * w / picture.width).coerceAtLeast(1)
                top = h / 2 - down / 2
            }
        }
        val out = IntArray(w * h) { AvsFrame.OPAQUE }
        for (y in maxOf(top, 0) until minOf(top + down, h)) {
            for (x in maxOf(left, 0) until minOf(left + across, w)) {
                out[y * w + x] = picture.at((x - left) * picture.width / across, (y - top) * picture.height / down)
            }
        }
        return out
    }

    companion object {
        fun read(body: ByteArray): PictureRenderer {
            val reader = BodyReader(body)
            val enabled = reader.int32Or(1) != 0
            val additive = reader.int32Or(0) != 0
            val average = reader.int32Or(1) != 0
            val adaptive = reader.int32Or(0) != 0
            val persist = reader.int32Or(0)
            val name = reader.cString()
            val keepRatio = reader.int32Or(0) != 0
            val fitHeight = reader.int32Or(0) != 0
            return PictureRenderer(Settings(enabled, additive, average, adaptive, persist, name, keepRatio, fitHeight))
        }
    }
}

/**
 * A picture stretched over the whole frame and blended with it, with one blend
 * for a frame with a beat and another for the rest.
 *
 * Transcribed from vis_avs `e_picture2.cpp`. The stretch is nearest-pixel or
 * smooth, again chosen separately for a beat.
 */
internal class Picture2Renderer(
    private val name: String,
    private val blend: Int,
    private val beatBlend: Int,
    private val smooth: Boolean,
    private val beatSmooth: Boolean,
    private val adjust: Int,
    private val beatAdjust: Int,
) : AvsComponentRenderer,
    AvsPictured {
    private var image: AvsImage? = null
    private var nearest = IntArray(0)
    private var smoothed = IntArray(0)
    private var stretchedFor = 0L

    override fun attach(images: AvsImages): String? {
        if (name.isEmpty()) return null
        image = images.load(name)
        return if (image == null) name else null
    }

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        val picture = image ?: return
        val w = frame.width
        val h = frame.height
        val key = w.toLong() shl Int.SIZE_BITS or h.toLong()
        if (stretchedFor != key) {
            nearest = IntArray(w * h) { picture.at(it % w * picture.width / w, it / w * picture.height / h) }
            smoothed =
                IntArray(w * h) {
                    picture.between(
                        (it % w) * picture.width.toDouble() / w - HALF,
                        (it / w) * picture.height.toDouble() / h - HALF,
                    )
                }
            stretchedFor = key
        }
        val source = if (if (state.beat) beatSmooth else smooth) smoothed else nearest
        val mode = MODES.getOrNull(if (state.beat) beatBlend else blend) ?: return
        val weight = (if (state.beat) beatAdjust else adjust).coerceIn(0, FULL)
        val pixels = frame.pixels
        for (i in pixels.indices) pixels[i] = AvsBlend.pixel(mode, source[i], pixels[i], weight)
    }

    companion object {
        private const val HALF = 0.5
        private const val FULL = 255

        /** The blend numbers a file stores, in order. A number past the list leaves the frame alone. */
        private val MODES =
            listOf(
                AvsBlendMode.REPLACE,
                AvsBlendMode.ADDITIVE,
                AvsBlendMode.MAXIMUM,
                AvsBlendMode.MINIMUM,
                AvsBlendMode.FIFTY_FIFTY,
                AvsBlendMode.SUB_DEST_SRC,
                AvsBlendMode.SUB_SRC_DEST,
                AvsBlendMode.MULTIPLY,
                AvsBlendMode.XOR,
                AvsBlendMode.ADJUSTABLE,
            )

        /** A 260-byte file name, then six ints: two blends, two smooth flags, two weights. */
        fun read(body: ByteArray): Picture2Renderer {
            val name = fixedName(body, 0, PATH_SIZE)
            val reader = BodyReader(body).also { it.skip(PATH_SIZE) }
            return Picture2Renderer(
                name = name,
                blend = reader.int32Or(0),
                beatBlend = reader.int32Or(0),
                smooth = reader.int32Or(0) != 0,
                beatSmooth = reader.int32Or(0) != 0,
                adjust = reader.int32Or(0),
                beatAdjust = reader.int32Or(0),
            )
        }
    }
}

/**
 * Stamps a picture on every pixel of the frame that is not black, centered on
 * that pixel and added to what is there.
 *
 * Transcribed from vis_avs `e_texer.cpp`. The frame it starts from is the one
 * that came in, or black. With "colorize" each stamp is tinted by the color of
 * the pixel it sits on. Only the first so many lit pixels, row by row, get a
 * stamp.
 */
internal class TexerRenderer(
    private val name: String,
    private val overInput: Boolean,
    private val colorize: Boolean,
    private val stamps: Int,
) : AvsComponentRenderer,
    AvsPictured {
    private var image: AvsImage? = null
    private var incoming = IntArray(0)

    override fun attach(images: AvsImages): String? {
        if (name.isEmpty()) return null
        image = images.load(name)
        return if (image == null) name else null
    }

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        val picture = image ?: return
        val w = frame.width
        val h = frame.height
        val out = frame.pixels
        if (incoming.size != out.size) incoming = IntArray(out.size)
        out.copyInto(incoming)
        if (!overInput) out.fill(AvsFrame.OPAQUE)
        var stamped = 0
        for (at in incoming.indices) {
            if (stamped >= stamps) return
            val under = incoming[at]
            if (under and RGB == 0) continue
            stamp(picture, out, w, h, at % w, at / w, if (colorize) under else WHITE)
            stamped++
        }
    }

    @Suppress("LongParameterList") // one stamp: the picture, the frame, where, and its tint
    private fun stamp(
        picture: AvsImage,
        out: IntArray,
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        tint: Int,
    ) {
        val left = x - picture.width / 2
        val top = y - picture.height / 2
        for (py in maxOf(0, -top) until minOf(picture.height, h - top)) {
            for (px in maxOf(0, -left) until minOf(picture.width, w - left)) {
                var texel = picture.pixels[py * picture.width + px]
                if (tint != WHITE) texel = AvsBlend.pixel(AvsBlendMode.MULTIPLY, tint, texel)
                val at = (top + py) * w + left + px
                out[at] = AvsBlend.pixel(AvsBlendMode.ADDITIVE, texel, out[at])
            }
        }
    }

    companion object {
        private const val RGB = 0xFFFFFF
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val UNUSED = 16
        private const val OVER_INPUT = 2
        private const val COLORIZE = 8
        private const val DEFAULT_STAMPS = 100

        /** Sixteen unused bytes, a 260-byte file name, the two modes in one int, and how many stamps. */
        fun read(body: ByteArray): TexerRenderer {
            val name = fixedName(body, UNUSED, PATH_SIZE)
            val reader = BodyReader(body).also { it.skip(UNUSED + PATH_SIZE) }
            val modes = reader.int32Or(0)
            val stamps = reader.int32Or(DEFAULT_STAMPS)
            return TexerRenderer(name, modes and 0x3 == OVER_INPUT, modes and 0xC == COLORIZE, stamps)
        }
    }
}
