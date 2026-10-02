// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * Warps the frame through a piece of ns-eel, once per grid vertex.
 *
 * A preset picks one of AVS's built-in effects by number, or carries its own
 * script; the built-ins are scripts too (see [MovementEffects]).
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

        fun read(
            body: ByteArray,
            eel: Eel,
        ): MovementRenderer? =
            parse(body)?.let {
                MovementRenderer(
                    code = it.effect.code,
                    coordinates = it.effect.coordinates,
                    blend = if (it.fiftyFifty) AvsBlendMode.FIFTY_FIFTY else AvsBlendMode.REPLACE,
                    wrap = it.wrap,
                    eel = eel,
                )
            }

        /**
         * Reads Movement's body.
         *
         * The effect id comes first. Zero means "look at the end of the block",
         * which is where AVS puts ids above 15, so that older builds reading
         * the file see a 0 and show "None". 0x7FFF means the preset carries
         * its own script.
         *
         * This is the parse alone, so a JVM test can call it without the
         * evaluator's native library.
         */
        internal fun parse(body: ByteArray): ParsedMovement? {
            val reader = BodyReader(body)
            val oldId = reader.int32()
            val custom = if (oldId == MovementEffects.CUSTOM) customCode(body) else null
            val settings = settings(body, custom?.consumed ?: 0)
            if (!settings.ok) return null

            val effect = effectFor(oldId, body, custom, settings)
            return effect?.let { ParsedMovement(it, settings.fiftyFifty, settings.wrap) }
        }

        /** A preset's own script: a new-version marker, then a sized string, or a fixed 256 bytes. */
        private fun customCode(body: ByteArray): CustomCode? {
            val reader = BodyReader(body)
            reader.int32()
            val marker = reader.byte()
            if (marker == CUSTOM_NEW_VERSION) {
                val cursor = SizedText(body, INT_SIZE + 1)
                // the marker byte is part of what the code cost: the settings
                // that follow start one byte later than the cursor says
                return cursor.sized()?.let { CustomCode(it, cursor.consumed + 1) }
            }
            // the old fixed-256 form: a "!rect " prefix marks cartesian and the
            // code fills the remaining 250 bytes (e_movement.cpp's load_legacy).
            // The prefix is only stripped here: the coordinates settings int
            // overrides it in AVS too, and settings() requires that int
            val prefixed = rectPrefixed(body)
            val from = INT_SIZE + if (prefixed) RECT_PREFIX.length else 0
            val cursor = SizedText(body, from)
            return cursor.fixed(LEGACY_CODE - if (prefixed) RECT_PREFIX.length else 0)?.let {
                CustomCode(it, LEGACY_CODE)
            }
        }

        private fun rectPrefixed(body: ByteArray): Boolean {
            if (body.size < INT_SIZE + RECT_PREFIX.length) return false
            return RECT_PREFIX.indices.all { body[INT_SIZE + it].toInt().toChar() == RECT_PREFIX[it] }
        }

        private fun effectFor(
            oldId: Int,
            body: ByteArray,
            custom: CustomCode?,
            settings: Settings,
        ): MovementEffect? =
            when {
                custom != null -> {
                    MovementEffect(
                        "Custom",
                        custom.code,
                        // a custom script uses the coordinates the file says
                        if (settings.cartesian) AvsCoordinates.CARTESIAN else AvsCoordinates.POLAR,
                    )
                }

                oldId == 0 -> {
                    MovementEffects[newId(body)]?.takeIf { it.runnable }
                }

                else -> {
                    MovementEffects[oldId]?.takeIf { it.runnable }
                }
            }

        /** Ids above 15 live past the five settings, where an old AVS would not look. */
        private fun newId(body: ByteArray): Int {
            val reader = BodyReader(body)
            repeat(SETTINGS_BEFORE_NEW_ID) { reader.int32() }
            val id = reader.int32()
            return if (reader.ok) id else 0
        }

        private const val INT_SIZE = 4
        private const val LEGACY_CODE = 256
        private const val SETTINGS_BEFORE_NEW_ID = 6

        private fun settings(
            body: ByteArray,
            codeBytes: Int,
        ): Settings {
            val reader = BodyReader(body)
            reader.int32() // the effect id, already read
            repeat(codeBytes) { reader.byte() }
            val fiftyFifty = reader.int32() != 0
            reader.int32() // source mapped: skipped
            val cartesian = reader.int32() != 0
            reader.int32() // bilinear: skipped
            val wrap = reader.int32() != 0
            return Settings(reader.ok, fiftyFifty, cartesian, wrap)
        }

        private data class Settings(
            val ok: Boolean,
            val fiftyFifty: Boolean,
            val cartesian: Boolean,
            val wrap: Boolean,
        )

        private class CustomCode(
            val code: String,
            val consumed: Int,
        )
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

    private fun int32(at: Int) =
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
