// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import nl.mattix.andamp.backend.media3.graph.BuiltInGraphs
import nl.mattix.andamp.backend.media3.graph.BundledPlugins
import nl.mattix.andamp.core.dsp.FrameProcessor
import nl.mattix.andamp.core.dsp.GraphParam
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.ParamValues
import nl.mattix.andamp.core.plugin.PluginSpec

/**
 * Every effect the rack can run, and how one is made.
 *
 * Every stage is a graph. A built-in effect's graph is built from
 * [BuiltInGraphs] and a plug-in's comes from its Lua file. The hand-written
 * stages in the test sources are the reference the karaoke and modulation
 * graphs are tested against.
 *
 * Defaults are read from the effect's own declaration.
 */
internal object BuiltInStages {
    // A stage that runs a graph. Its controls arrive by index, in the order
    // they were declared, which is the order the rack renders them.
    class Graphed(
        private val engine: FrameProcessor,
        private val declared: List<GraphParam>,
    ) : DspStage {
        override fun process(frame: FloatArray) = engine.process(frame)

        override fun reset() {
            engine.reset()
            fresh = true
        }

        /**
         * A control the rack has not stored falls back to its declared
         * default, which is also what the rack draws for it.
         */
        fun update(params: ParamValues) {
            declared.forEachIndexed { index, param -> engine.setParameter(index, params[param.id, param.default]) }
            if (fresh) {
                // A new or reset engine holds the declared defaults. It settles
                // on the first values it is given, so it does not glide from
                // the defaults after a seek or a structural rebuild.
                engine.settleParameters()
                fresh = false
            }
        }

        /** True until the first [update] after creation or a [reset]. */
        private var fresh = true
    }

    /** Every effect on offer for this stream: the built-in ones, then the loaded plug-ins. */
    fun catalogue(
        sampleRate: Int,
        channels: Int,
        installed: List<String> = emptyList(),
    ): List<EffectSpec> = BuiltInEffects.all + plugins(sampleRate, channels, installed).map { BundledPlugins.describe(it) }

    /** The format [shippedPluginIds] reads at; any format would do. */
    private const val ID_RATE = 44_100
    private const val ID_CHANNELS = 2

    private fun plugins(
        sampleRate: Int,
        channels: Int,
        installed: List<String>,
    ): List<PluginSpec> = Loaded.forFormat(sampleRate, channels, installed)

    /**
     * The ids of the plug-ins that ship, as loaded for one fixed format.
     *
     * An id is in the file's header and does not depend on the format. The
     * list is empty until [preload] has run for that format with nothing
     * installed.
     */
    fun shippedPluginIds(): List<String> = plugins(ID_RATE, ID_CHANNELS, emptyList()).map { it.id }

    /** True when [preload] has nothing left to do for this format. */
    fun ready(
        sampleRate: Int,
        channels: Int,
        installed: List<String>,
    ): Boolean = Loaded.has(sampleRate, channels, installed)

    /**
     * Loads what this format needs on the calling thread, which must not be
     * the audio thread or the one configuring a stream.
     */
    fun preload(
        sampleRate: Int,
        channels: Int,
        installed: List<String>,
    ) = Loaded.fill(sampleRate, channels, installed)

    /**
     * What has been loaded from Lua, kept per format.
     *
     * A graph is built for a fixed rate and channel count, so the format is
     * part of the key; so are the installed sources, so a plug-in added while
     * the app runs gets a load of its own. Written on the loading thread and
     * read on the one that configures a stream, hence the concurrent map.
     */
    private object Loaded {
        private val byFormat = java.util.concurrent.ConcurrentHashMap<String, List<PluginSpec>>()

        private fun key(
            sampleRate: Int,
            channels: Int,
            installed: List<String>,
        ) = "$sampleRate/$channels/${installed.hashCode()}"

        /**
         * Never loads: loading runs Lua, and the caller may be the thread
         * that configures a stream.
         */
        fun forFormat(
            sampleRate: Int,
            channels: Int,
            installed: List<String>,
        ): List<PluginSpec> = byFormat[key(sampleRate, channels, installed)] ?: emptyList()

        fun has(
            sampleRate: Int,
            channels: Int,
            installed: List<String>,
        ): Boolean = byFormat.containsKey(key(sampleRate, channels, installed))

        fun fill(
            sampleRate: Int,
            channels: Int,
            installed: List<String>,
        ) {
            byFormat.computeIfAbsent(key(sampleRate, channels, installed)) {
                BundledPlugins.load(sampleRate, channels, installed)
            }
        }
    }

    /**
     * A stage for this slot. [params] are read only for structural controls,
     * which choose which nodes exist and so are arguments to the build.
     */
    fun create(
        pluginId: String,
        sampleRate: Int,
        channels: Int,
        params: ParamValues = ParamValues.EMPTY,
        installed: List<String> = emptyList(),
    ): DspStage? =
        plugins(sampleRate, channels, installed)
            .firstOrNull { it.id == pluginId }
            ?.let { plugin ->
                BundledPlugins.engine(plugin.graph)?.let { Graphed(it, plugin.params) }
            } ?: builtIn(pluginId, sampleRate, channels, params)

    /**
     * A built-in effect's stage for this stream. Building allocates the delay
     * lines, so it happens here once and not on every rack change.
     */
    private fun builtIn(
        pluginId: String,
        sampleRate: Int,
        channels: Int,
        params: ParamValues,
    ): DspStage? =
        when (pluginId) {
            BuiltInEffects.KARAOKE -> {
                graphed(BuiltInEffects.karaoke, sampleRate, channels, BuiltInGraphs::karaoke)
            }

            BuiltInEffects.MODULATION -> {
                graphed(BuiltInEffects.modulation, sampleRate, channels) { spec, rate, count ->
                    BuiltInGraphs.modulation(spec, rate, count, modeOf(params.of(spec, "mode")))
                }
            }

            BuiltInEffects.REVERB -> {
                graphed(BuiltInEffects.reverb, sampleRate, channels, BuiltInGraphs::reverb)
            }

            else -> {
                null
            }
        }

    private fun graphed(
        spec: EffectSpec,
        sampleRate: Int,
        channels: Int,
        build: (EffectSpec, Int, Int) -> nl.mattix.andamp.core.dsp.GraphSpec,
    ): DspStage? = BundledPlugins.engine(build(spec, sampleRate, channels))?.let { Graphed(it, BuiltInGraphs.params(spec)) }

    /**
     * Hands a stage its values.
     */
    fun apply(
        stage: DspStage,
        params: ParamValues,
    ) {
        (stage as? Graphed)?.update(params)
    }

    private fun ParamValues.of(
        spec: EffectSpec,
        paramId: String,
    ): Float = this[paramId, spec.params.first { it.id == paramId }.default]

    /** A choice arrives as its option's value; one outside the list is clamped into it. */
    private fun modeOf(value: Float): Int = value.toInt().coerceIn(0, MODES - 1)

    /** Chorus, flanger, phaser. */
    private const val MODES = 3
}
