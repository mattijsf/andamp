// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * Runs one Super Scope.
 *
 * Four pieces of ns-eel, in the order AVS runs them: `init` once, `perFrame`
 * every frame, `onBeat` on a beat, and `perPoint` once per point with `i`
 * walking 0..1 and `v` carrying the sample. The point code writes `x` and `y` in
 * -1..1, which is what gets drawn.
 *
 * Frame and point behavior transcribed from vis_avs `e_superscope.cpp`
 * (BSD; see NOTICE.md).
 */
internal class SuperScopeRenderer(
    private val config: SuperScopeConfig,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    // n is set by the host once, here (e_superscope.cpp primes
    // *vars.n = 100.0 on recompile only). After that it is the preset's own
    // variable, so a fractional accumulation like n=n*1.01 carries across
    // frames
    private val n = eel.variable("n").also { it.value = DEFAULT_POINTS }
    private val i = eel.variable("i")
    private val v = eel.variable("v")
    private val x = eel.variable("x")
    private val y = eel.variable("y")
    private val red = eel.variable("red")
    private val green = eel.variable("green")
    private val blue = eel.variable("blue")
    private val beat = eel.variable("b")
    private val width = eel.variable("w")
    private val height = eel.variable("h")
    private val skip = eel.variable("skip")
    private val drawMode = eel.variable("drawmode")
    private val lineSize = eel.variable("linesize")

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

    private var started = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        // a scope with no colors is inert: nothing draws and none of its code
        // runs (e_superscope.cpp:109 returns before the sections execute)
        if (config.colours.isEmpty()) return

        width.value = frame.width.toDouble()
        height.value = frame.height.toDouble()
        beat.value = if (state.beat) 1.0 else 0.0
        skip.value = 0.0
        // the raw config int, as AVS primes it ((uint32_t)draw_mode), set
        // again every frame: the scripts may overwrite it, and the draw
        // branch below reads the variable, not the config
        drawMode.value = config.drawMode.toUInt().toDouble()
        // the render mode's line width is the default; the scripts may overwrite
        lineSize.value = state.lineSize.toDouble()
        // the palette color is written to red/green/blue once per frame,
        // before the frame sections, and not again per point, so a frame
        // section that assigns them colors every point
        val colour = cycle.next()
        red.value = ((colour shr 16) and 0xFF) / FULL
        green.value = ((colour shr 8) and 0xFF) / FULL
        blue.value = (colour and 0xFF) / FULL

        if (!started) {
            started = true
            init?.run()
        }
        perFrame?.run()
        if (state.beat) onBeat?.run()

        // the point loop only runs when the point code compiled: a scope
        // without one still runs its other sections but draws nothing
        // (e_superscope.cpp:168, code_point.is_valid())
        if (perPoint == null) return
        // read back after the frame sections, truncated like the C int cast;
        // n at or below zero draws nothing
        var points = n.value.toInt()
        if (points > MAX_POINTS) points = MAX_POINTS
        drawPoints(frame, audio, points, state)
    }

    private fun drawPoints(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        points: Int,
        state: AvsRenderState,
    ) {
        var isFirstPoint = true
        var lastX = 0
        var lastY = 0
        for (point in 0 until points) {
            v.value = audio.scopeValueAt(config.source, point, points)
            i.value = if (points == 1) 0.0 else point.toDouble() / (points - 1)
            skip.value = 0.0
            perPoint?.run()

            // no negation: AVS's scope space is screen-down (y = -1 is the top
            // row), the same convention WarpMesh warps in
            val px = scopeToPixel(x.value, frame.width)
            val py = scopeToPixel(y.value, frame.height)
            when (scopeMark(drawMode.value, skip.value, isFirstPoint)) {
                ScopeMark.DOT -> {
                    // one pixel: linesize applies to lines only
                    AvsDraw.dot(frame, px, py, pointColour(), state.renderBlend, state.renderAdjust)
                }

                ScopeMark.LINE -> {
                    AvsDraw.line(
                        frame,
                        lastX,
                        lastY,
                        px,
                        py,
                        pointColour(),
                        // read per point, rounded half-up like AVS's
                        // (int)(linesize + 0.5), so point code that writes
                        // linesize tapers the stroke. AVS caps at 255; the
                        // cap here is 16, so that a damaged value cannot
                        // fill the frame.
                        (lineSize.value + HALF).toInt().coerceIn(1, MAX_LINE_SIZE),
                        state.renderBlend,
                        state.renderAdjust,
                    )
                }

                ScopeMark.NONE -> {
                    Unit
                }
            }
            isFirstPoint = false
            lastX = px
            lastY = py
        }
    }

    private fun pointColour(): Int =
        AvsFrame.OPAQUE or (scopeChannel(red.value) shl 16) or (scopeChannel(green.value) shl 8) or scopeChannel(blue.value)

    /** AVS walks the palette over time, interpolating; [ScopeColourWalk] is that walk. */
    private val cycle = ScopeColourWalk(config.colours)

    /**
     * A variable's value, so a test can see what the preset's own code did.
     */
    internal fun debugVariable(name: String) = eel.variable(name).value

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    private companion object {
        const val DEFAULT_POINTS = 100.0

        /** AVS's own cap: 128 * 1024 points (e_superscope.cpp:173). */
        const val MAX_POINTS = 128 * 1024

        const val MAX_LINE_SIZE = 16
        const val FULL = 255.0
        const val HALF = 0.5
    }
}

/** What one scope point puts on the frame, if anything. */
internal enum class ScopeMark { NONE, DOT, LINE }

/**
 * The per-point draw decision, transcribed from vis_avs `e_superscope.cpp:188-210`
 * (BSD; see NOTICE.md): a point draws when `skip < 0.00001` (negatives draw,
 * and a NaN skips because the comparison is false); then `drawmode < 0.00001`
 * is a one-pixel dot and anything else is a line, except that the first point
 * only seeds the line's start and draws nothing.
 */
internal fun scopeMark(
    drawMode: Double,
    skip: Double,
    isFirstPoint: Boolean,
): ScopeMark =
    when {
        !(skip < SCOPE_EPSILON) -> ScopeMark.NONE
        drawMode < SCOPE_EPSILON -> ScopeMark.DOT
        isFirstPoint -> ScopeMark.NONE
        else -> ScopeMark.LINE
    }

/**
 * -1..1 across the frame, 0 in the middle: `(v + 1) * span * 0.5`, truncated
 * toward zero like the C int cast (e_superscope.cpp:186). A value of 1 lands at
 * span, which is off the frame and is clipped.
 */
internal fun scopeToPixel(
    value: Double,
    span: Int,
): Int {
    // a preset's own math can produce NaN, on which C's cast is undefined; -1
    // is off the frame, so AvsDraw clips it
    if (value.isNaN()) return -1
    return ((value + 1.0) * span * SCOPE_HALF).toInt()
}

/**
 * A color channel from a 0..1 variable, truncated: vis_avs `makeint`
 * (e_superscope.cpp:85-93). red = 0.5 is 127, not 128. A NaN fails both
 * comparisons and truncates to 0.
 */
internal fun scopeChannel(value: Double): Int =
    when {
        value <= 0.0 -> 0
        value >= 1.0 -> SCOPE_CHANNEL_FULL
        else -> (value * SCOPE_CHANNEL_FULL).toInt()
    }

/**
 * The palette walk, transcribed from vis_avs `e_superscope.cpp:139-154` (BSD;
 * see NOTICE.md). AVS advances the counter before reading it, and blends the
 * two neighboring colors with integer weights `(63 - r)` and `r` over 64. The
 * weights sum to 63/64, so even a single-color palette renders a step under
 * full brightness (255 comes out 251).
 *
 * Only call [next] with a non-empty palette.
 */
internal class ScopeColourWalk(
    private val colours: List<Int>,
) {
    private var position = 0

    fun next(): Int {
        position++
        if (position >= colours.size * STEPS) position = 0
        val at = position / STEPS
        val mix = position % STEPS
        val from = colours[at]
        val to = if (at + 1 < colours.size) colours[at + 1] else colours[0]
        return AvsFrame.OPAQUE or
            (channel(from, to, mix, RED_SHIFT) shl RED_SHIFT) or
            (channel(from, to, mix, GREEN_SHIFT) shl GREEN_SHIFT) or
            channel(from, to, mix, 0)
    }

    private fun channel(
        from: Int,
        to: Int,
        mix: Int,
        shift: Int,
    ): Int = (((from shr shift) and 0xFF) * (STEPS - 1 - mix) + ((to shr shift) and 0xFF) * mix) / STEPS

    private companion object {
        /** Frames from one color to the next. */
        const val STEPS = 64
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
    }
}

/** The literal 0.00001 both of AVS's scope gates compare against. */
private const val SCOPE_EPSILON = 0.00001
private const val SCOPE_HALF = 0.5
private const val SCOPE_CHANNEL_FULL = 255
