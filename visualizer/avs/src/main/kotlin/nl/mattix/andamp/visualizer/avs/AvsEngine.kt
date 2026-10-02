// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * The pipeline every AVS component runs in.
 *
 * A frame starts as the last one, or black when the preset says to clear, and
 * each component in turn reads and writes it. An Effect List does the same to a
 * canvas of its own and folds the result back under its blend modes.
 *
 * A preset is [load]ed once, which compiles its code and works out which
 * components can run, and is then rendered per frame.
 *
 * Kotlin over an `IntArray`, apart from the ns-eel evaluator under the scripted
 * components, so most of it is tested on the JVM.
 */
class AvsEngine(
    width: Int,
    height: Int,
) : AutoCloseable {
    var frame = AvsFrame(width, height)
        private set

    private var buffers = AvsBuffers(width, height)
    private var loaded: AvsPreset? = null
    private var nodes: List<Node> = emptyList()
    private var state = AvsRenderState(buffers)

    /**
     * Components in the loaded preset that got no renderer, by name, once each, and scripted
     * ones whose code did not all compile, with the failed sections.
     */
    var unimplemented: List<String> = emptyList()
        private set

    fun resize(
        width: Int,
        height: Int,
    ) {
        if (frame.width == width && frame.height == height) return
        frame = AvsFrame(width, height)
        buffers = buffers.resizedTo(width, height)
        state = AvsRenderState(buffers)
    }

    /**
     * The runtime tree: one node per component position in the preset.
     *
     * Two identical Roto Blitters in one preset are two spinners with two
     * angles, so a renderer belongs to a position and is not shared between
     * value-equal components. Each Effect List node carries its own on-beat
     * countdown for the same reason.
     */
    private sealed interface Node {
        class Leaf(
            val renderer: AvsComponentRenderer,
        ) : Node

        class Group(
            val config: AvsEffectListConfig,
            val children: List<Node>,
        ) : Node {
            var beatFramesLeft = 0

            /**
             * The list's own canvas, kept across frames as AVS keeps it;
             * `clear every frame` is the list's own setting. It does not start
             * as a copy of the frame below: under an additive output blend
             * that would add the frame to itself every frame.
             */
            var canvas: AvsFrame? = null

            fun canvasFor(like: AvsFrame): AvsFrame {
                val existing = canvas
                if (existing != null && existing.sameSizeAs(like)) return existing
                return AvsFrame(like.width, like.height).also { canvas = it }
            }
        }
    }

    /**
     * Compiles [preset] and works out what of it can run.
     *
     * [unimplemented] is complete when this returns. A scripted component with
     * sections that did not compile is reported and kept, so the sections that
     * compiled still run.
     */
    fun load(preset: AvsPreset) {
        releaseRenderers()
        loaded = preset
        val missing = linkedSetOf<String>()
        nodes = build(preset.components, missing)
        unimplemented = missing.toList()
    }

    private fun build(
        components: List<AvsComponent>,
        missing: MutableSet<String>,
    ): List<Node> =
        components.mapNotNull { component ->
            if (component is AvsComponent.EffectList) {
                Node.Group(component.config, build(component.components, missing))
            } else {
                val renderer = rendererFor(component)
                if (renderer == null) {
                    missing += component.name
                    null
                } else {
                    if (renderer is AvsScripted && renderer.errors.isNotEmpty()) {
                        missing += "${component.name} (${renderer.errors.joinToString()})"
                    }
                    Node.Leaf(renderer)
                }
            }
        }

    /**
     * The renderer for [component], or null when [READERS] has none for its
     * name or its reader cannot read the body.
     */
    private fun rendererFor(component: AvsComponent): AvsComponentRenderer? {
        // by name, so that an APE, which has no id and names itself in the
        // file, is looked up in the same table as the built-ins
        if (component is AvsComponent.EffectList || component is AvsComponent.Unknown) return null
        return READERS[component.name]?.invoke(component.body)
    }

    /** Runs one frame of the loaded preset and returns the buffer it drew into. */
    fun render(audio: AvsAudioFrame = AvsAudioFrame()): AvsFrame {
        val preset = loaded ?: return frame
        if (preset.clearEveryFrame) frame.clear()
        // every frame starts at the default render mode; a Set Render Mode
        // inside the preset changes it until the end of the frame
        state.reset(audio.beat)
        run(nodes, frame, audio)
        return frame
    }

    private fun run(
        nodes: List<Node>,
        target: AvsFrame,
        audio: AvsAudioFrame,
    ) {
        nodes.forEach { node ->
            when (node) {
                is Node.Group -> renderList(node, target, audio)
                is Node.Leaf -> node.renderer.render(target, audio, state)
            }
        }
    }

    /**
     * One Effect List frame, transcribed from `e_effectlist.cpp` (vis_avs,
     * BSD; see NOTICE.md):
     *
     * - a list is on when `enabled` OR its on-beat countdown is running - the
     *   flags are independent, and an off list with on-beat set flashes in;
     * - REPLACE-in/REPLACE-out is the special case with no canvas at all: the
     *   children draw straight onto the frame below (this is how the root
     *   behaves, and how a plain grouping list costs nothing);
     * - otherwise the list's own canvas persists across frames, is freed the
     *   moment the list goes disabled, and the input blend decides what of
     *   the frame below comes in - including BUFFER, a per-pixel mask blend
     *   through a global buffer, one-based in the file;
     * - each list zeroes the render mode for its children and restores the
     *   outer one afterwards, and a beat rewritten inside stays inside.
     */
    private fun renderList(
        list: Node.Group,
        target: AvsFrame,
        audio: AvsAudioFrame,
    ) {
        val config = list.config
        if (state.beat && config.onlyOnBeat) list.beatFramesLeft = config.onBeatFrames
        val enabledThisFrame = config.enabled || list.beatFramesLeft > 0
        list.beatFramesLeft--
        if (!enabledThisFrame) {
            // the original frees the list's framebuffer here: a disabled
            // stretch does not preserve the canvas
            list.canvas = null
            return
        }

        val saved = state.save()
        state.zeroRenderMode()
        try {
            if (config.input == AvsBlendMode.REPLACE && config.output == AvsBlendMode.REPLACE) {
                // the no-canvas special case
                list.canvas = null
                if (config.clearFrame) target.clear()
                run(list.children, target, audio)
                return
            }

            val canvas = list.canvasFor(target)
            if (config.clearFrame) canvas.clear()

            blendIn(config, target, canvas)
            run(list.children, canvas, audio)
            blendOut(config, canvas, target)
        } finally {
            state.restore(saved)
        }
    }

    private fun blendIn(
        config: AvsEffectListConfig,
        target: AvsFrame,
        canvas: AvsFrame,
    ) {
        if (config.input == AvsBlendMode.IGNORE) return
        if (config.input == AvsBlendMode.BUFFER) {
            // one-based in the file, like every buffer field in the format
            val buffer = buffers.peek(config.inBuffer - 1) ?: return
            if (buffer.sameSizeAs(canvas)) maskBlend(target, canvas, buffer, config.inBufferInvert)
            return
        }
        AvsBlend.blend(config.input, target, canvas, config.inAdjust.coerceIn(0, FULL))
    }

    private fun blendOut(
        config: AvsEffectListConfig,
        canvas: AvsFrame,
        target: AvsFrame,
    ) {
        if (config.output == AvsBlendMode.BUFFER) {
            val buffer = buffers.peek(config.outBuffer - 1) ?: return
            if (buffer.sameSizeAs(target)) maskBlend(canvas, target, buffer, config.outBufferInvert)
            return
        }
        AvsBlend.blend(config.output, canvas, target, config.outAdjust.coerceIn(0, FULL))
    }

    /**
     * `blend_buffer` from blend.cpp: an adjustable blend whose weight is each
     * buffer pixel's brightest channel - the buffer is a mask, invert flips it.
     */
    private fun maskBlend(
        source: AvsFrame,
        destination: AvsFrame,
        mask: AvsFrame,
        invert: Boolean,
    ) {
        for (i in destination.pixels.indices) {
            val m = mask.pixels[i]
            var weight = maxOf((m shr 16) and 0xFF, (m shr 8) and 0xFF, m and 0xFF)
            if (invert) weight = FULL - weight
            destination.pixels[i] = AvsBlend.pixel(AvsBlendMode.ADJUSTABLE, source.pixels[i], destination.pixels[i], weight)
        }
    }

    private fun releaseRenderers() {
        fun release(node: Node) {
            when (node) {
                is Node.Leaf -> node.renderer.close()
                is Node.Group -> node.children.forEach(::release)
            }
        }
        nodes.forEach(::release)
        nodes = emptyList()
    }

    override fun close() {
        releaseRenderers()
        loaded = null
    }

    companion object {
        private const val FULL = 255

        /**
         * Hands [read] the evaluator its renderer will own, and closes that
         * evaluator when [read] returns null: a native context has no
         * finalizer.
         */
        private fun scripted(read: (Eel) -> AvsComponentRenderer?): AvsComponentRenderer? {
            val eel = Eel()
            var made: AvsComponentRenderer? = null
            try {
                made = read(eel)
            } finally {
                if (made == null) eel.close()
            }
            return made
        }

        /**
         * Every component this build runs, by the name AVS's own editor shows,
         * and the reader that turns a body into a renderer.
         *
         * A reader gives null for a body it cannot read, and [load] then lists
         * the component in [unimplemented]. Effect List is absent because the
         * engine runs it itself.
         */
        private val READERS: Map<String, (ByteArray) -> AvsComponentRenderer?> =
            mapOf(
                "Super Scope" to { body -> SuperScopeReader.read(body)?.let { SuperScopeRenderer(it) } },
                "Invert" to { body -> InvertRenderer.read(body) },
                "FadeOut" to { body -> FadeOutRenderer.read(body) },
                "Blur" to { body -> BlurRenderer.read(body) },
                "Buffer Save" to { body -> BufferSaveRenderer.read(body) },
                "Set Render Mode" to { body -> SetRenderModeRenderer.read(body) },
                "Comment" to { _ -> CommentRenderer() },
                "Movement" to { body -> scripted { MovementRenderer.read(body, it) } },
                "Moving Particle" to { body -> MovingParticleRenderer.read(body) },
                "Custom BPM" to { body -> CustomBpmRenderer.read(body) },
                "Dynamic Movement" to { body -> DynamicMovementRenderer.read(body)?.let { DynamicMovementRenderer(it) } },
                "Fast Brightness" to { body -> FastBrightnessRenderer.read(body) },
                "Brightness" to { body -> BrightnessRenderer.read(body) },
                "Colorfade" to { body -> ColorfadeRenderer.read(body) },
                "Color Clip" to { body -> ColorClipRenderer.read(body) },
                "Unique Tone" to { body -> UniqueToneRenderer.read(body) },
                "Mirror" to { body -> MirrorRenderer.read(body) },
                "Clear Screen" to { body -> ClearScreenRenderer.read(body) },
                "OnBeat Clear" to { body -> OnBeatClearRenderer.read(body) },
                "Mosaic" to { body -> MosaicRenderer.read(body) },
                "Interleave" to { body -> InterleaveRenderer.read(body) },
                "Blitter Feedback" to { body -> BlitterFeedbackRenderer.read(body) },
                "Roto Blitter" to { body -> RotoBlitterRenderer.read(body) },
                "Dynamic Shift" to { body -> scripted { DynamicShiftRenderer.read(body, it) } },
                "Dynamic Distance Modifier" to { body -> scripted { DynamicDistanceModifierRenderer.read(body, it) } },
                "Color Modifier" to { body -> scripted { ColorModifierRenderer.read(body, it) } },
                "Grain" to { body -> GrainRenderer.read(body) },
                "Simple" to { body -> SimpleRenderer.read(body) },
                "Timescope" to { body -> TimescopeRenderer.read(body) },
                "Dot Grid" to { body -> DotGridRenderer.read(body) },
                "Ring" to { body -> RingRenderer.read(body) },
                "Starfield" to { body -> StarfieldRenderer.read(body) },
                "Bass Spin" to { body -> BassSpinRenderer.read(body) },
                // Color Reduction and Channel Shift are APEs: named in the file, with no id
                "Color Reduction" to { body -> ColorReductionRenderer.read(body) },
                "Channel Shift" to { body -> ChannelShiftRenderer.read(body) },
                "Interferences" to { body -> InterferencesRenderer.read(body) },
            )

        /** The names of the components this build can run. */
        val supported: Set<String> get() = READERS.keys

        /** Whether every component of [preset] is one this build has. */
        fun canRun(preset: AvsPreset): Boolean = preset.flatten().all { it.name == "Effect List" || it.name in supported }
    }
}
