// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * A component the engine can run.
 *
 * A component reads and writes the frame it is handed. The [AvsRenderState] is
 * what is shared between them: the buffer bank a Buffer Save writes into, and
 * the render mode a Set Render Mode leaves behind for whatever draws next.
 */
interface AvsComponentRenderer : AutoCloseable {
    fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    )

    /** Most components hold nothing that needs releasing. */
    override fun close() = Unit
}

/**
 * A renderer that carries preset-written code. The engine reports the sections
 * in [errors] and keeps the renderer, so the sections that compiled still run.
 */
interface AvsScripted {
    /** The code sections that would not compile, by name; empty when all did. */
    val errors: List<String>
}

/**
 * What one frame of one preset shares between its components.
 *
 * The render mode is state: Set Render Mode is a Misc component that draws
 * nothing and changes how everything after it puts its marks down, until
 * another one changes it again.
 */
class AvsRenderState(
    val buffers: AvsBuffers,
) {
    /**
     * Whether this frame is a beat, as the components see it. Custom BPM
     * rewrites the beat for everything after it, so this can differ from the
     * audio frame's own flag.
     */
    var beat: Boolean = false
    var renderBlend: AvsBlendMode = AvsBlendMode.REPLACE
        private set

    var renderAdjust: Int = FULL
        private set

    /** What a Render component draws with when the preset has not said otherwise. */
    var lineSize: Int = 1
        private set

    fun setRenderMode(
        blend: AvsBlendMode,
        adjust: Int,
        lineSize: Int,
    ) {
        renderBlend = blend
        renderAdjust = adjust.coerceIn(0, FULL)
        this.lineSize = lineSize.coerceIn(1, MAX_LINE_SIZE)
    }

    /** Back to the defaults, which is where every frame starts. */
    fun reset(beat: Boolean = false) {
        this.beat = beat
        renderBlend = AvsBlendMode.REPLACE
        renderAdjust = FULL
        lineSize = 1
    }

    /**
     * What an Effect List saves on the way in and restores on the way out:
     * the original zeroes `g_line_blend_mode` for each list's children and
     * puts the outer value back afterwards, and a beat rewrite inside a list
     * is local to it. Transcribed from e_effectlist.cpp (vis_avs, BSD).
     */
    fun save(): Saved = Saved(renderBlend, renderAdjust, lineSize, beat)

    /** A list's children start from the zeroed render mode, keeping the outer beat. */
    fun zeroRenderMode() {
        renderBlend = AvsBlendMode.REPLACE
        renderAdjust = FULL
        lineSize = 1
    }

    fun restore(saved: Saved) {
        renderBlend = saved.blend
        renderAdjust = saved.adjust
        lineSize = saved.lineSize
        beat = saved.beat
    }

    /** One list's worth of scoped state. */
    class Saved internal constructor(
        internal val blend: AvsBlendMode,
        internal val adjust: Int,
        internal val lineSize: Int,
        internal val beat: Boolean,
    )

    private companion object {
        const val FULL = 255

        /** The original clamps line width to 1..255. */
        const val MAX_LINE_SIZE = 255
    }
}
