// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * Code that draws nothing and sets the preset's shared values: the hundred
 * `reg00` to `reg99` and `gmegabuf`, which every component's code can read.
 *
 * After vis_avs `e_globalvariables.cpp` (BSD; see NOTICE.md), a rewrite of
 * Jheriko's Global Variables plug-in. The plug-in can also write those values
 * to a file and read them back from one, on the PC it ran on. There is no such
 * file here, so `load` and `save` are variables the code may set and nothing
 * follows from them.
 */
internal class GlobalVariablesRenderer(
    init: String,
    perFrame: String,
    onBeat: String,
    private val eel: Eel = Eel(),
) : AvsComponentRenderer,
    AvsScripted {
    private val width = eel.variable("w")
    private val height = eel.variable("h")
    private val beat = eel.variable("b")
    private val load = eel.variable("load")
    private val save = eel.variable("save")

    private val initCode = compile(init)
    private val frameCode = compile(perFrame)
    private val beatCode = compile(onBeat)

    override val errors: List<String> =
        buildList {
            if (MovementEffect.hasCode(init) && initCode == null) add("init")
            if (MovementEffect.hasCode(perFrame) && frameCode == null) add("per frame")
            if (MovementEffect.hasCode(onBeat) && beatCode == null) add("on beat")
        }

    private var started = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!started) {
            started = true
            initCode?.run()
            // the plug-in sets these once, after its init code has run
            width.value = frame.width.toDouble()
            height.value = frame.height.toDouble()
            beat.value = if (state.beat) 1.0 else 0.0
            load.value = 0.0
            save.value = 0.0
        }
        frameCode?.run()
        if (state.beat) beatCode?.run()
    }

    private fun compile(source: String) = MovementEffect.asEel(source).takeIf { it.isNotBlank() }?.let { eel.compile(it) }

    override fun close() = eel.close()

    companion object {
        private const val UNUSED = 24

        /** When to load, 24 unused bytes, then three pieces of code, each ending in a null: init, per frame, on beat. */
        internal fun parse(body: ByteArray): List<String> {
            val reader = BodyReader(body)
            reader.int32Or(0)
            reader.skip(UNUSED)
            return List(3) { reader.cString() }
        }

        fun read(
            body: ByteArray,
            eel: Eel,
        ): GlobalVariablesRenderer {
            val (init, perFrame, onBeat) = parse(body)
            return GlobalVariablesRenderer(init, perFrame, onBeat, eel)
        }
    }
}
