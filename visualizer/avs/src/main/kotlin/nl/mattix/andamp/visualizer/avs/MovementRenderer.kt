// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * Warps the frame through a piece of ns-eel, once per grid vertex.
 *
 * A preset picks one of AVS's built-in effects by number, or carries its own
 * script; the built-ins are scripts too (see [MovementEffects]), apart from
 * the two that move single pixels, which [MovementTableRenderer] runs.
 *
 * The code has no per-frame section: a Movement's warp is the same every frame,
 * so the mesh is evaluated once per frame size and then only sampled. AVS
 * evaluates it per pixel into a static table; the grid is an approximation
 * (see [WarpMesh]). The source-mapped mode (the warp inverted into a scatter,
 * `e_movement.cpp`'s `source_map`) is not implemented.
 */
internal class MovementRenderer(
    private val code: String,
    private val coordinates: AvsCoordinates,
    private val blend: AvsBlendMode,
    private val wrap: Boolean,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    private val x = eel.variable("x")
    private val y = eel.variable("y")
    private val d = eel.variable("d")
    private val r = eel.variable("r")
    private val screenWidth = eel.variable("sw")
    private val screenHeight = eel.variable("sh")

    private val compiled =
        MovementEffect.asEel(code).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    /** Whether the preset's script did not compile. */
    val failed = code.isNotBlank() && compiled == null

    override val errors: List<String> get() = if (failed) listOf("script") else emptyList()

    private var mesh: WarpMesh? = null
    private var meshWidth = 0
    private var meshHeight = 0
    private var source: AvsFrame? = null
    private var built = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (compiled == null) return
        val mesh = meshFor(frame)
        val scratch = sourceFor(frame)

        // rebuilt whenever the frame changes size: a resize changes the grid
        // and the values of sw and sh
        if (!built) {
            built = true
            screenWidth.value = frame.width.toDouble()
            screenHeight.value = frame.height.toDouble()
            buildMesh(mesh, frame)
        }

        scratch.copyFrom(frame)
        mesh.sample(scratch, frame)
        if (blend != AvsBlendMode.REPLACE) AvsBlend.blend(blend, scratch, frame)
    }

    private fun buildMesh(
        mesh: WarpMesh,
        frame: AvsFrame,
    ) {
        mesh.evaluate(frame.width, frame.height, wrap) { px, py, pd, pr, out ->
            x.value = px
            y.value = py
            d.value = pd
            r.value = pr
            compiled?.run()
            if (coordinates == AvsCoordinates.POLAR) {
                out.fromPolar(d.value, r.value)
            } else {
                out.x = x.value
                out.y = y.value
            }
        }
    }

    private fun meshFor(frame: AvsFrame): WarpMesh {
        val existing = mesh
        if (existing != null && meshWidth == frame.width && meshHeight == frame.height) return existing
        meshWidth = frame.width
        meshHeight = frame.height
        built = false
        val columns = (frame.width / GRID_STEP).coerceAtLeast(MIN_GRID)
        val rows = (frame.height / GRID_STEP).coerceAtLeast(MIN_GRID)
        return WarpMesh(columns, rows).also { mesh = it }
    }

    override fun close() = eel.close()

    private fun sourceFor(frame: AvsFrame): AvsFrame {
        val existing = source
        if (existing != null && existing.sameSizeAs(frame)) return existing
        return AvsFrame(frame.width, frame.height).also { source = it }
    }

    companion object {
        /** One vertex per this many pixels. */
        private const val GRID_STEP = 8

        /**
         * Never coarser than this, however small the frame: a 2x2 grid
         * interpolates a swirl into identity.
         */
        private const val MIN_GRID = 8

        private const val CUSTOM_NEW_VERSION = 1

        /** The oldest presets mark a cartesian custom script with this prefix on the code itself. */
        private const val RECT_PREFIX = "!rect "

        /** The scripted renderer for [body], or null when it holds no script this build runs. */
        fun read(
            body: ByteArray,
            eel: Eel,
        ): MovementRenderer? = parse(body)?.takeIf { it.effect.table == null }?.let { of(it, eel) }

        fun of(
            parsed: ParsedMovement,
            eel: Eel,
        ) = MovementRenderer(
            code = parsed.effect.code,
            coordinates = parsed.effect.coordinates,
            blend = if (parsed.fiftyFifty) AvsBlendMode.FIFTY_FIFTY else AvsBlendMode.REPLACE,
            wrap = parsed.wrap,
            eel = eel,
        )

        /**
         * Reads Movement's body. Transcribed from vis_avs `r_trans.cpp`
         * `load_config` (BSD; see NOTICE.md).
         *
         * The effect id comes first. 0x7FFF means the preset carries its own
         * script, as a length-counted string after a marker byte or, in the
         * oldest presets, as a fixed 256 bytes. Five settings follow, and a
         * body may end before any of them: older builds wrote fewer, and each
         * one missing keeps its default. A zero id is replaced by the id
         * stored after the settings, which is where AVS puts ids above 15.
         *
         * This is the parse alone, so a JVM test can call it without the
         * evaluator's native library.
         */
        internal fun parse(body: ByteArray): ParsedMovement? {
            val cursor = Cursor(body)
            var id = if (cursor.has(INT_SIZE)) cursor.int32() else MovementEffects.DEFAULT
            var cartesian = false
            var code = ""
            if (id == MovementEffects.CUSTOM) {
                // the oldest presets mark a cartesian script with a prefix on the code itself
                cartesian = cursor.startsWith(RECT_PREFIX)
                if (cartesian) cursor.skip(RECT_PREFIX.length)
                code = customCode(cursor, prefixed = cartesian) ?: return null
            }
            val fiftyFifty = cursor.has(INT_SIZE) && cursor.int32() != 0
            if (cursor.has(INT_SIZE)) cursor.int32() // source mapped: not implemented
            if (cursor.has(INT_SIZE)) cartesian = cursor.int32() != 0
            if (cursor.has(INT_SIZE)) cursor.int32() // bilinear: not implemented
            val wrap = cursor.has(INT_SIZE) && cursor.int32() != 0
            if (id == 0 && cursor.has(INT_SIZE)) id = cursor.int32()

            val effect =
                if (id == MovementEffects.CUSTOM) {
                    MovementEffect("Custom", code, if (cartesian) AvsCoordinates.CARTESIAN else AvsCoordinates.POLAR)
                } else {
                    // an id outside the table is "None" in the original
                    MovementEffects[id] ?: MovementEffects[0]
                }
            return effect?.takeIf { it.runnable }?.let { ParsedMovement(it, fiftyFifty, wrap) }
        }

        /** A preset's own script, in either form; null when its length cannot be right. */
        private fun customCode(
            cursor: Cursor,
            prefixed: Boolean,
        ): String? =
            when {
                cursor.peek() == CUSTOM_NEW_VERSION -> {
                    cursor.skip(1)
                    cursor.sized()
                }

                // the fixed form is 256 bytes with the prefix counted in
                cursor.has(LEGACY_CODE) -> {
                    cursor.fixed(LEGACY_CODE - if (prefixed) RECT_PREFIX.length else 0)
                }

                else -> {
                    ""
                }
            }

        private const val INT_SIZE = 4
        private const val LEGACY_CODE = 256

        /** Walks a body, with the length checks the original makes before each read. */
        private class Cursor(
            private val bytes: ByteArray,
        ) {
            private var at = 0

            fun has(count: Int) = bytes.size - at >= count

            fun peek(): Int = if (has(1)) bytes[at].toInt() and 0xFF else -1

            fun skip(count: Int) {
                at += count
            }

            fun int32(): Int = SizedText(bytes, at).int32(at).also { at += INT_SIZE }

            fun startsWith(prefix: String) =
                has(prefix.length) && prefix.indices.all { bytes[at + it].toInt().toChar() == prefix[it] }

            /** A length and that many bytes; null when the length cannot be right. */
            fun sized(): String? {
                val text = SizedText(bytes, at)
                return text.sized()?.also { at += text.consumed }
            }

            fun fixed(length: Int): String {
                val text = SizedText(bytes, at)
                return text.fixed(length).orEmpty().also { at += text.consumed }
            }
        }
    }

    /** What a Movement body says. */
    internal data class ParsedMovement(
        val effect: MovementEffect,
        val fiftyFifty: Boolean,
        val wrap: Boolean,
    )
}

/** Reads a string that carries its own length, or one of a fixed size, and says how many bytes it took. */
internal class SizedText(
    private val bytes: ByteArray,
    private val from: Int,
) {
    var consumed = 0
        private set

    fun sized(): String? {
        if (from + INT_SIZE > bytes.size) return null
        val length = int32(from)
        // compared against the room left: an end offset would overflow for a huge length
        if (length < 0 || length > bytes.size - from - INT_SIZE) return null
        consumed = INT_SIZE + length
        return text(from + INT_SIZE, length)
    }

    fun fixed(length: Int): String? {
        if (length < 0 || length > bytes.size - from) return null
        consumed = length
        return text(from, length)
    }

    internal fun int32(at: Int) =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun text(
        at: Int,
        length: Int,
    ): String {
        val end = (at until at + length).firstOrNull { bytes[it].toInt() == 0 } ?: (at + length)
        return String(bytes, at, end - at, Charsets.ISO_8859_1)
    }

    private companion object {
        const val INT_SIZE = 4
    }
}
