// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A picture drawn many times a frame, wherever the preset's code puts it: a
 * Super Scope whose points are stamps.
 *
 * After vis_avs `e_texer2.cpp` (BSD; see NOTICE.md), which is Steven Wittens'
 * Texer II plug-in. The code sets `n`, how many stamps, and per stamp its
 * place `x`, `y` in -1..1, its size `sizex`, `sizey` as a multiple of the
 * picture's own, and a color `red`, `green`, `blue` that tints it. A negative
 * size mirrors the picture on that axis. Stamps are drawn with the render
 * mode. `v` is the waveform at that stamp's place along the frame's samples,
 * and `iw`, `ih` the picture's size.
 *
 * That source draws each stamp with hand-written assembly per blend mode; this
 * draws the same rectangle, sampling the picture between its pixels when it
 * is resized. With no picture named, or one that is not there, the stamp is
 * the plug-in's own: a soft white dot 21 pixels across.
 */
internal class Texer2Renderer(
    private val settings: Settings,
    init: String,
    perFrame: String,
    onBeat: String,
    perStamp: String,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted,
    AvsPictured {
    internal data class Settings(
        /** Presets from AVS 2.81d wrap a stamp once; later ones wrap it however far out it is. */
        val wrapsOnce: Boolean = false,
        val name: String = "",
        val resize: Boolean = false,
        val wrap: Boolean = false,
        val colorize: Boolean = true,
    )

    private val count = eel.variable("n")
    private val index = eel.variable("i")
    private val x = eel.variable("x")
    private val y = eel.variable("y")
    private val value = eel.variable("v")
    private val beat = eel.variable("b")
    private val width = eel.variable("w")
    private val height = eel.variable("h")
    private val imageWidth = eel.variable("iw")
    private val imageHeight = eel.variable("ih")
    private val sizeX = eel.variable("sizex")
    private val sizeY = eel.variable("sizey")
    private val red = eel.variable("red")
    private val green = eel.variable("green")
    private val blue = eel.variable("blue")
    private val skip = eel.variable("skip")

    private val initCode = compile(init)
    private val frameCode = compile(perFrame)
    private val beatCode = compile(onBeat)
    private val stampCode = compile(perStamp)

    override val errors: List<String> =
        buildList {
            if (MovementEffect.hasCode(init) && initCode == null) add("init")
            if (MovementEffect.hasCode(perFrame) && frameCode == null) add("per frame")
            if (MovementEffect.hasCode(onBeat) && beatCode == null) add("on beat")
            if (MovementEffect.hasCode(perStamp) && stampCode == null) add("per point")
        }

    private var image: AvsImage = DEFAULT_IMAGE
    private var started = false

    /** A picture that is not there is not reported: the plug-in draws its own dot in its place. */
    override fun attach(images: AvsImages): String? {
        if (settings.name.isNotEmpty() && settings.name != DEFAULT_NAME) image = images.load(settings.name) ?: DEFAULT_IMAGE
        return null
    }

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        prime(frame, state.beat)
        if (!started) {
            started = true
            count.value = 0.0
            initCode?.run()
        }
        frameCode?.run()
        if (state.beat) beatCode?.run()
        val stamps = count.value.roundToInt().coerceIn(0, MAX_STAMPS)
        if (stamps <= 0) return
        val step = 1.0 / (stamps - 1)
        var at = 0.0
        for (stamp in 0 until stamps) {
            index.value = at
            skip.value = 0.0
            value.value = audio.waveform.getOrElse(stamp * (AvsAudioFrame.SAMPLES - 1) / stamps) { 0f }.toDouble()
            at += step
            stampCode?.run()
            val across = abs(sizeX.value)
            val down = abs(sizeY.value)
            if (skip.value != 0.0 || across <= SMALLEST || down <= SMALLEST) continue
            place(frame, state, across, down)
        }
    }

    private fun prime(
        frame: AvsFrame,
        isBeat: Boolean,
    ) {
        index.value = 0.0
        x.value = 0.0
        y.value = 0.0
        value.value = 0.0
        width.value = frame.width.toDouble()
        height.value = frame.height.toDouble()
        beat.value = if (isBeat) 1.0 else 0.0
        imageWidth.value = image.width.toDouble()
        imageHeight.value = image.height.toDouble()
        sizeX.value = 1.0
        sizeY.value = 1.0
        red.value = 1.0
        green.value = 1.0
        blue.value = 1.0
        skip.value = 0.0
    }

    /** One stamp where the code put it, and again across an edge it overlaps when the preset wraps. */
    private fun place(
        frame: AvsFrame,
        state: AvsRenderState,
        across: Double,
        down: Double,
    ) {
        val tint = if (settings.colorize) pixelOf(level(red.value), level(green.value), level(blue.value)) else WHITE
        var px = x.value
        var py = y.value
        if (settings.wrap) {
            val overX: Boolean
            val overY: Boolean
            if (settings.wrapsOnce) {
                overX = overlapsEdge(px, across, image.width, frame.width)
                overY = overlapsEdge(py, down, image.height, frame.height)
                px -= onceBack(px)
                py -= onceBack(py)
            } else {
                px -= (px / 2.0).roundToInt() * 2.0
                py -= (py / 2.0).roundToInt() * 2.0
                overX = overlapsEdge(px, across, image.width, frame.width)
                overY = overlapsEdge(py, down, image.height, frame.height)
            }
            val otherX = px - if (px > 0) 2.0 else -2.0
            val otherY = py - if (py > 0) 2.0 else -2.0
            if (overX) stamp(frame, state, otherX, py, tint)
            if (overY) stamp(frame, state, px, otherY, tint)
            if (overX && overY) stamp(frame, state, otherX, otherY, tint)
        }
        stamp(frame, state, px, py, tint)
    }

    private fun onceBack(coordinate: Double) =
        when {
            coordinate > 1.0 -> 2.0
            coordinate < -1.0 -> -2.0
            else -> 0.0
        }

    /** Whether a stamp centered at [coordinate] reaches over the frame's edge on that axis. */
    private fun overlapsEdge(
        coordinate: Double,
        size: Double,
        imagePixels: Int,
        framePixels: Int,
    ): Boolean {
        val half = size * (imagePixels - 1) / framePixels
        return abs(coordinate) + half > 1.0 && abs(coordinate) - half < 1.0
    }

    private fun stamp(
        frame: AvsFrame,
        state: AvsRenderState,
        atX: Double,
        atY: Double,
        tint: Int,
    ) {
        val w = frame.width
        val h = frame.height
        val centreX = (atX * HALF + HALF) * (w - 1)
        val centreY = (atY * HALF + HALF) * (h - 1)
        val across = if (settings.resize) (image.width - 1) * abs(sizeX.value) else (image.width - 1).toDouble()
        val down = if (settings.resize) (image.height - 1) * abs(sizeY.value) else (image.height - 1).toDouble()
        val left = if (settings.resize) centreX - across * HALF else (centreX.roundToInt() - (image.width - 1) / 2).toDouble()
        val top = if (settings.resize) centreY - down * HALF else (centreY.roundToInt() - (image.height - 1) / 2).toDouble()
        val firstX = left.roundToInt().coerceAtLeast(0)
        val lastX = (left + across).roundToInt().coerceAtMost(w - 1)
        val firstY = top.roundToInt().coerceAtLeast(0)
        val lastY = (top + down).roundToInt().coerceAtMost(h - 1)
        if (lastX < firstX || lastY < firstY) return
        if (across <= 0.0 || down <= 0.0) return
        // the picture is walked in 16.16 fixed point: where the first pixel reads from, and the step per pixel
        val stepU = ((image.width - 1) / across * ONE).toInt().let { if (sizeX.value < 0) -it else it }
        val stepV = ((image.height - 1) / down * ONE).toInt().let { if (sizeY.value < 0) -it else it }
        val startU = fixed((firstX - left) / across * (image.width - 1), image.width, sizeX.value < 0)
        var v = fixed((firstY - top) / down * (image.height - 1), image.height, sizeY.value < 0)
        val mode = state.renderBlend
        val adjust = state.renderAdjust
        val pixels = frame.pixels
        for (py in firstY..lastY) {
            var u = startU
            var at = py * w + firstX
            for (px in firstX..lastX) {
                var texel =
                    if (settings.resize) {
                        image.between(u, v)
                    } else {
                        image.at(
                            (u + HALF_ONE) shr FIXED,
                            (v + HALF_ONE) shr FIXED,
                        )
                    }
                if (tint != WHITE) texel = AvsBlend.pixel(AvsBlendMode.MULTIPLY, tint, texel)
                pixels[at] = if (mode == AvsBlendMode.REPLACE) texel else AvsBlend.pixel(mode, texel, pixels[at], adjust)
                at++
                u += stepU
            }
            v += stepV
        }
    }

    /** A place in the picture as 16.16 fixed point, counted from the far side when the stamp is mirrored. */
    private fun fixed(
        place: Double,
        size: Int,
        mirrored: Boolean,
    ): Int = ((if (mirrored) size - 1 - place else place) * ONE).toInt()

    private fun level(colour: Double) = (colour * FULL).roundToInt().coerceIn(0, FULL)

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    companion object {
        private const val MAX_STAMPS = 65_536
        private const val SMALLEST = 0.01
        private const val HALF = 0.5
        private const val FULL = 255
        private const val FIXED = 16
        private const val ONE = 65_536.0
        private const val HALF_ONE = 32_768
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val PATH_SIZE = 260
        private const val INT_SIZE = 4
        private const val CURRENT_VERSION = 1
        private const val DEFAULT_NAME = "(default image)"
        private const val DEFAULT_SIDE = 21

        /** The plug-in's own stamp, row by row, two hex digits a pixel: a blurred white disc. */
        private val DEFAULT_ROWS =
            listOf(
                "00000000000003080d1012100d0803010000000000",
                "00000000030b16212a2f322f2a21160b0300000000",
                "00000005122232414d5457544d4132221205000000",
                "00000514283e5365747d807d7466533e2814050000",
                "00031128425d778d9ca5a8a59c8d775d4228110300",
                "000a213e5d7d9aafc0cacdcac0af9a7d5d3e210a00",
                "031531537699b6cedee8ebe8decdb6997653311503",
                "071f40658cafcde5f2f8faf8f2e5cdaf8c65401f07",
                "0c284c739bbfddf2fafdfefdfaf2ddbf9b734c280b",
                "0f2e537ca4c8e7f8fdfffffffdf8e7c8a47c532e0f",
                "1030557ea6cbe9f9fefffffffef9e9cba67e553010",
                "0f2d527ba3c7e6f7fdfefffefdf7e6c7a37b522d0f",
                "0b284a719abddcf1fafdfefdfaf1dcbd9a714a280b",
                "061f3f638aadcbe3f1f7f9f7f1e3cbad8a633f1f06",
                "031430507496b3cadbe5e8e5dbcbb3967450301402",
                "0009203c5a7a96acbcc6c9c6bcac967a5a3c200900",
                "000210263f5a738999a1a4a19989735a4026100200",
                "00000412263b506270797c797062503b2612040000",
                "000000040f1f2f3e49505350493e2f1f0f04000000",
                "000000000209131e262c2e2c261d13090200000000",
                "00000000000002060a0e0f0e0a0603000000000000",
            )

        internal val DEFAULT_IMAGE: AvsImage =
            AvsImage(
                DEFAULT_SIDE,
                DEFAULT_SIDE,
                DEFAULT_ROWS
                    .flatMap { row -> row.chunked(2).map { it.toInt(radix = 16) } }
                    .map { pixelOf(it, it, it) }
                    .toIntArray(),
            )

        internal class Parsed(
            val settings: Settings,
            val init: String,
            val perFrame: String,
            val onBeat: String,
            val perStamp: String,
        )

        /**
         * The body: a version, a 260-byte file name, three flags and an unused
         * int, then four pieces of code, each with its length before it: init,
         * per frame, on beat, per stamp.
         */
        internal fun parse(body: ByteArray): Parsed {
            val reader = BodyReader(body)
            val version = reader.int32Or(CURRENT_VERSION)
            val nameEnd = (INT_SIZE until minOf(INT_SIZE + PATH_SIZE, body.size)).firstOrNull { body[it].toInt() == 0 }
            val name =
                if (body.size > INT_SIZE) {
                    String(body, INT_SIZE, (nameEnd ?: minOf(INT_SIZE + PATH_SIZE, body.size)) - INT_SIZE, Charsets.ISO_8859_1)
                } else {
                    ""
                }
            reader.skip(PATH_SIZE)
            val resize = reader.int32Or(0) != 0
            val wrap = reader.int32Or(0) != 0
            val colorize = reader.int32Or(1) != 0
            reader.skip(INT_SIZE)
            val code = List(4) { reader.string() }
            return Parsed(Settings(version != CURRENT_VERSION, name, resize, wrap, colorize), code[0], code[1], code[2], code[3])
        }

        fun read(
            body: ByteArray,
            eel: Eel,
        ): Texer2Renderer {
            val parsed = parse(body)
            return Texer2Renderer(parsed.settings, parsed.init, parsed.perFrame, parsed.onBeat, parsed.perStamp, eel)
        }
    }
}
