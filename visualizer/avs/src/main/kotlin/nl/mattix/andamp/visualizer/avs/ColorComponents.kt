// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import java.util.Random

// Four components that change only a pixel's color: one driven by ns-eel, two
// APEs, and one that adds noise.
//
// Field layouts transcribed from grandchild/AVS-File-Decoder (MIT); arithmetic
// transcribed from the vis_avs source (BSD 3-clause, Nullsoft) unless a
// component says otherwise; see NOTICE.md.

/**
 * Colors that walk through a list, a step per frame. The arithmetic is
 * [ScopeColourWalk]; this adds the white fallback for an empty list that the
 * Render components use.
 */
internal class ColourCycle(
    colours: List<Int>,
) {
    private val walk = if (colours.isEmpty()) null else ScopeColourWalk(colours)

    fun next(): Int = walk?.next() ?: (AvsFrame.OPAQUE or WHITE)

    private companion object {
        const val WHITE = 0xFFFFFF
    }
}

/**
 * A channel curve the preset writes itself.
 *
 * The code runs once per value, not once per pixel: a 256-entry table per
 * channel is built by setting `red = green = blue = i/255` and reading the
 * three back, and the frame is then three table lookups per pixel.
 *
 * Sweeping all three channels together is a reconstruction from the
 * component's example scripts (they read `red` and write `red`); the decoder
 * does not record it.
 */
internal class ColorModifierRenderer(
    init: String,
    perFrame: String,
    onBeat: String,
    private val perPointSource: String,
    private val recomputeEveryFrame: Boolean,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    private val red = eel.variable("red")
    private val green = eel.variable("green")
    private val blue = eel.variable("blue")
    private val beat = eel.variable("b")

    private val initCode = compile(init)
    private val frameCode = compile(perFrame)
    private val beatCode = compile(onBeat)
    private val pointCode = compile(perPointSource)

    override val errors: List<String> =
        buildList {
            if (init.isNotBlank() && initCode == null) add("init")
            if (perFrame.isNotBlank() && frameCode == null) add("per frame")
            if (onBeat.isNotBlank() && beatCode == null) add("on beat")
            if (perPointSource.isNotBlank() && pointCode == null) add("per point")
        }

    private val redTable = IntArray(LEVELS)
    private val greenTable = IntArray(LEVELS)
    private val blueTable = IntArray(LEVELS)
    private var built = false
    private var started = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (pointCode == null) return
        beat.value = if (state.beat) 1.0 else 0.0
        if (!started) {
            started = true
            initCode?.run()
        }
        frameCode?.run()
        if (state.beat) beatCode?.run()

        // tables are built once unless the preset asks for a rebuild every
        // frame, even when per-frame code exists
        if (!built || recomputeEveryFrame) {
            buildTables(pointCode)
            built = true
        }

        for (i in frame.pixels.indices) {
            val pixel = frame.pixels[i]
            frame.pixels[i] = pixelOf(redTable[redOf(pixel)], greenTable[greenOf(pixel)], blueTable[blueOf(pixel)])
        }
    }

    private fun buildTables(code: EelCode) {
        for (i in 0 until LEVELS) {
            val value = i.toDouble() / (LEVELS - 1)
            red.value = value
            green.value = value
            blue.value = value
            code.run()
            redTable[i] = level(red.value)
            greenTable[i] = level(green.value)
            blueTable[i] = level(blue.value)
        }
    }

    private fun level(value: Double) = (value * (LEVELS - 1)).toInt().coerceIn(0, LEVELS - 1)

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    companion object {
        private const val LEVELS = 256
        private const val SECTIONS = 4

        /** The code is stored as per point, per frame, on beat, init. */
        fun read(
            body: ByteArray,
            eel: Eel,
        ): ColorModifierRenderer? {
            if (body.isEmpty()) return null
            val current = body[0].toInt() == 1
            val reader = CodeSectionReader(body, if (current) 1 else 0, current)
            val code = List(SECTIONS) { reader.next() }
            if (!reader.ok) return null
            // the flag is the last field, and old presets end before it
            val recompute = reader.rest().int32() != 0
            return ColorModifierRenderer(code[3], code[1], code[2], code[0], recompute, eel)
        }
    }
}

/**
 * Fewer colors, by throwing away the low bits of every channel.
 *
 * An APE, so it names itself in the file instead of taking an id. Its body
 * opens with 260 unused bytes, the size of a Windows `MAX_PATH`.
 */
internal class ColorReductionRenderer(
    levels: Int,
) : AvsComponentRenderer {
    private val mask = (0xFF shl (BITS - bitsFor(levels))) and 0xFF

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (mask == 0xFF) return
        for (i in frame.pixels.indices) {
            frame.pixels[i] = AvsFrame.OPAQUE or (frame.pixels[i] and (mask shl 16 or (mask shl 8) or mask))
        }
    }

    companion object {
        private const val BITS = 8
        private const val UNUSED_PATH = 260

        private fun bitsFor(levels: Int): Int {
            var bits = 0
            var value = levels.coerceIn(2, 256)
            while (value > 1) {
                value = value shr 1
                bits++
            }
            return bits
        }

        fun read(body: ByteArray): ColorReductionRenderer? {
            if (body.size < UNUSED_PATH) return null
            val reader = BodyReader(body.copyOfRange(UNUSED_PATH, body.size))
            val key = reader.int32()
            if (!reader.ok || key !in 1..BITS) return null
            return ColorReductionRenderer(1 shl key)
        }
    }
}

/**
 * The three channels, in another order.
 *
 * The keys are not 0..5: this APE stores a control id, so the numbering is the
 * four-digit values in `ORDERS`, taken from AVS-File-Decoder. An unrecognized
 * key leaves the channels alone. With the second field set, every beat picks a
 * permutation uniformly from all six, the identity included, and it stays until
 * the next beat (e_channelshift.cpp, BSD; see NOTICE.md).
 */
internal class ChannelShiftRenderer(
    order: String,
    private val onBeatRandom: Boolean = false,
    private val random: Random = Random(SEED),
) : AvsComponentRenderer {
    private var shifts = shiftsFor(order)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (state.beat && onBeatRandom) shifts = MODES[random.nextInt(MODES.size)]
        if (shifts.size != CHANNELS || shifts == IDENTITY) return
        for (i in frame.pixels.indices) {
            val pixel = frame.pixels[i]
            frame.pixels[i] =
                pixelOf(
                    (pixel shr shifts[0]) and 0xFF,
                    (pixel shr shifts[1]) and 0xFF,
                    (pixel shr shifts[2]) and 0xFF,
                )
        }
    }

    companion object {
        private const val CHANNELS = 3
        private val SHIFT_OF = mapOf('R' to 16, 'G' to 8, 'B' to 0)
        private val IDENTITY = listOf(16, 8, 0)
        private const val SEED = 0x63686966L

        private fun shiftsFor(order: String) = order.map { SHIFT_OF[it] ?: 0 }

        /** The six permutations in CHANSHIFT_MODE enum order, 0 the identity; `rand() % 6` picks from them. */
        private val MODES = listOf("RGB", "GBR", "BRG", "RBG", "BGR", "GRB").map(::shiftsFor)

        private val ORDERS =
            mapOf(
                1018 to "GBR",
                1019 to "BRG",
                1020 to "RBG",
                1021 to "BGR",
                1022 to "GRB",
            )

        fun read(body: ByteArray): ChannelShiftRenderer? {
            val reader = BodyReader(body)
            val mode = reader.int32()
            if (!reader.ok) return null
            // the second int is on-beat random; e_channelshift.cpp reads it
            // only when the body is long enough, so absent means off
            val onBeatRandom = reader.int32() != 0
            return ChannelShiftRenderer(ORDERS[mode] ?: "RGB", onBeatRandom)
        }
    }
}

/**
 * Noise.
 *
 * Transcribed from vis_avs `e_grain.cpp` (BSD; see NOTICE.md). For every
 * non-black pixel a candidate color is drawn (black, or the pixel scaled by a
 * random `s/256`), the choice made by a random byte against
 * `amount * 255 / 100`, and the candidate is then blended: replace mode keeps
 * only the chosen pixels, dimmed, on black; additive brightens the chosen ones
 * and leaves the rest alone; 50/50 halves the unchosen. Static grain fixes
 * each pixel's (scale, threshold) pair until the frame size changes, and its
 * thresholds run 0..99, so from amount 40 up every pixel is chosen.
 */
internal class GrainRenderer(
    private val enabled: Boolean,
    private val amount: Int,
    private val static: Boolean,
    private val blend: AvsBlendMode,
    private val random: Random = Random(SEED),
) : AvsComponentRenderer {
    /** Per pixel: a scale byte and a threshold byte, e_grain.cpp's depth buffer. */
    private var table = ByteArray(0)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        val amountScaled = amount * FULL / PERCENT
        if (static && table.size != frame.pixels.size * 2) rebuild(frame.pixels.size)
        for (i in frame.pixels.indices) {
            val pixel = frame.pixels[i]
            if (pixel and CHANNELS_MASK == 0) continue // the original touches only non-black pixels
            val chosen: Boolean
            val scale: Int
            if (static) {
                scale = table[i * 2].toInt() and 0xFF
                chosen = (table[i * 2 + 1].toInt() and 0xFF) < amountScaled
            } else {
                chosen = random.nextInt(BYTE_SPAN) < amountScaled
                scale = if (chosen) random.nextInt(BYTE_SPAN) else 0
            }
            val candidate =
                if (chosen) {
                    pixelOf(
                        (redOf(pixel) * scale) shr 8,
                        (greenOf(pixel) * scale) shr 8,
                        (blueOf(pixel) * scale) shr 8,
                    )
                } else {
                    AvsFrame.OPAQUE
                }
            frame.pixels[i] = if (blend == AvsBlendMode.REPLACE) candidate else AvsBlend.pixel(blend, candidate, pixel)
        }
    }

    private fun rebuild(pixels: Int) {
        table = ByteArray(pixels * 2)
        for (i in 0 until pixels) {
            // rand() % 255 and rand() % 100, as the original fills its buffer
            table[i * 2] = random.nextInt(FULL).toByte()
            table[i * 2 + 1] = random.nextInt(PERCENT).toByte()
        }
    }

    companion object {
        private const val FULL = 255
        private const val PERCENT = 100
        private const val BYTE_SPAN = 256
        private const val CHANNELS_MASK = 0xFFFFFF

        /** Fixed, so the grain is the same on every run of a test. */
        private const val SEED = 0x6772616EL

        fun read(body: ByteArray): GrainRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            // grain's own pair: the 50/50 int only counts when additive is not
            // set (`GET_INT() && blend_mode == 0`), the opposite priority of
            // pairBlend, and there is no default-render-blend option
            val additive = reader.int32() != 0
            val fifty = reader.int32() != 0
            val blend =
                when {
                    additive -> AvsBlendMode.ADDITIVE
                    fifty -> AvsBlendMode.FIFTY_FIFTY
                    else -> AvsBlendMode.REPLACE
                }
            val amount = reader.int32()
            val static = reader.int32() != 0
            return if (reader.ok) GrainRenderer(enabled, amount, static, blend) else null
        }
    }
}
