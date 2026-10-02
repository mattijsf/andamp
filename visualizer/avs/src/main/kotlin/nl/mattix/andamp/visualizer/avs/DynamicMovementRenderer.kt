// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * A Movement whose warp can change every frame.
 *
 * The same warp mesh, and the same four code sections a Super Scope has: `init`
 * once, `perFrame` every frame, `onBeat` on a beat, and `perPoint` at each grid
 * vertex. The mesh is evaluated every frame, and its grid size is the preset's
 * own.
 *
 * `alpha` follows `e_dynamicmovement.cpp` (transcribed from vis_avs, BSD; see
 * NOTICE.md): reset to 0.5 each frame before the frame code, not reset per
 * point, clamped per vertex, and, when the blend flag is on, interpolated
 * across cells to mix the warped pixel over the original per pixel.
 *
 * Two tail fields of the body are ignored: `buffer` (sample the warp from a
 * global buffer instead of the frame) and `alpha_only` (skip the warp and
 * blend the source in place at the per-vertex alpha).
 */
internal class DynamicMovementRenderer(
    private val config: DynamicMovementConfig,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    private val x = eel.variable("x")
    private val y = eel.variable("y")
    private val d = eel.variable("d")
    private val r = eel.variable("r")
    private val beat = eel.variable("b")
    private val width = eel.variable("w")
    private val height = eel.variable("h")
    private val alpha = eel.variable("alpha")

    private val init = compile(config.init)
    private val perFrame = compile(config.perFrame)
    private val onBeat = compile(config.onBeat)
    private val perPoint = compile(config.perPoint)

    /** The sections that did not compile. */
    override val errors: List<String> =
        buildList {
            if (config.init.isNotBlank() && init == null) add("init")
            if (config.perFrame.isNotBlank() && perFrame == null) add("per frame")
            if (config.onBeat.isNotBlank() && onBeat == null) add("on beat")
            if (config.perPoint.isNotBlank() && perPoint == null) add("per point")
        }

    private val mesh = WarpMesh(config.gridWidth, config.gridHeight)
    private var source: AvsFrame? = null
    private var started = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (perPoint == null) return
        // AVS's order: the init code runs before the frame variables are set,
        // then w/h/b land and alpha resets to its per-frame 0.5 default
        if (!started) {
            started = true
            init?.run()
        }
        width.value = frame.width.toDouble()
        height.value = frame.height.toDouble()
        beat.value = if (state.beat) 1.0 else 0.0
        alpha.value = WarpMesh.DEFAULT_ALPHA
        perFrame?.run()
        if (state.beat) onBeat?.run()

        mesh.evaluate(frame.width, frame.height, config.wrap) { px, py, pd, pr, out ->
            x.value = px
            y.value = py
            d.value = pd
            r.value = pr
            // alpha is not reset here: what the point code leaves in it
            // carries into the next vertex, as in AVS
            perPoint.run()
            if (config.coordinates == AvsCoordinates.POLAR) {
                out.fromPolar(d.value, r.value)
            } else {
                out.x = x.value
                out.y = y.value
            }
            out.alpha = alpha.value
        }

        val scratch = sourceFor(frame)
        scratch.copyFrom(frame)
        mesh.sample(scratch, frame, blend = config.blend)
    }

    private fun sourceFor(frame: AvsFrame): AvsFrame {
        val existing = source
        if (existing != null && existing.sameSizeAs(frame)) return existing
        return AvsFrame(frame.width, frame.height).also { source = it }
    }

    /** A variable's value, so a test can see what the preset's own code did. */
    internal fun debugVariable(name: String) = eel.variable(name).value

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    companion object {
        private const val SECTIONS = 4

        /** One cell. */
        private const val MIN_GRID = 1

        /** A bound so that a corrupt or hostile file cannot ask for a gigabyte of vertices. */
        private const val MAX_GRID = 512

        /**
         * The body: a version byte, four code sections, then the settings.
         *
         * The code is stored as per point, per frame, on beat, init.
         */
        fun read(body: ByteArray): DynamicMovementConfig? {
            if (body.isEmpty()) return null
            val current = body[0].toInt() == 1
            val reader = CodeSectionReader(body, if (current) 1 else 0, current)
            val code = List(SECTIONS) { reader.next() }
            val rest = reader.rest()

            rest.int32() // bilinear filtering: skipped
            val coordinates = if (rest.int32() == 1) AvsCoordinates.CARTESIAN else AvsCoordinates.POLAR
            val gridWidth = rest.int32().coerceIn(MIN_GRID, MAX_GRID)
            val gridHeight = rest.int32().coerceIn(MIN_GRID, MAX_GRID)
            val blend = rest.int32() != 0
            val wrap = rest.int32() != 0
            // buffer and alphaOnly follow and are not read

            return if (!reader.ok || !rest.ok) {
                null
            } else {
                DynamicMovementConfig(
                    init = code[3],
                    perFrame = code[1],
                    onBeat = code[2],
                    perPoint = code[0],
                    coordinates = coordinates,
                    gridWidth = gridWidth,
                    gridHeight = gridHeight,
                    blend = blend,
                    wrap = wrap,
                )
            }
        }
    }
}

/**
 * Code sections in a row, each sized or each a fixed 256 bytes.
 *
 * Dynamic Movement, Dynamic Shift, the Dynamic Distance Modifier and Color
 * Modifier all store their code this way and read it through this class.
 */
internal class CodeSectionReader(
    private val bytes: ByteArray,
    start: Int,
    private val sized: Boolean,
) {
    var at = start
        private set

    var ok = true
        private set

    fun next(): String {
        val cursor = SizedText(bytes, at)
        val text = if (sized) cursor.sized() else cursor.fixed(LEGACY_CODE)
        if (text == null) {
            ok = false
            return ""
        }
        at += cursor.consumed
        return text
    }

    /** The bytes past the code, for the component's own settings tail. */
    fun rest(): BodyReader = BodyReader(bytes.copyOfRange(at.coerceAtMost(bytes.size), bytes.size))

    private companion object {
        const val LEGACY_CODE = 256
    }
}

/** A Dynamic Movement's settings, read out of its body. */
internal data class DynamicMovementConfig(
    val init: String = "",
    val perFrame: String = "",
    val onBeat: String = "",
    val perPoint: String = "",
    val coordinates: AvsCoordinates = AvsCoordinates.CARTESIAN,
    val gridWidth: Int = 16,
    val gridHeight: Int = 16,
    val blend: Boolean = false,
    val wrap: Boolean = false,
)
