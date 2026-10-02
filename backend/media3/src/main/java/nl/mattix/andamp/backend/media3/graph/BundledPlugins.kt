// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import android.util.Log
import nl.mattix.andamp.core.dsp.FrameProcessor
import nl.mattix.andamp.core.dsp.GraphCompiler
import nl.mattix.andamp.core.dsp.GraphSpec
import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.ParamSpec
import nl.mattix.andamp.core.plugin.PluginLoader
import nl.mattix.andamp.core.plugin.PluginSpec

/**
 * The plug-ins this app ships with, read from their Lua files.
 *
 * They are loaded the way a listener's plug-in is: through [PluginLoader], in
 * the same sandbox, against the same deadline.
 *
 * A plug-in that fails to load is left out and logged, so it cannot stop the
 * player from starting.
 */
internal object BundledPlugins {
    private const val TAG = "AndAmpPlugins"

    /**
     * The files that ship: every file in the plugins resource folder. The Lua
     * version of Karaoke the tests use lives in the test resources.
     */
    internal val FILES =
        listOf("preamp.lua", "tone.lua", "virtualbass.lua", "width.lua", "leveler.lua", "crossfeed.lua")

    /**
     * Loads the bundled plug-ins and [installed], the Lua sources the listener
     * added, for one format: a graph is built for a fixed rate and channel
     * count. Both go through the same loader, and a plug-in that fails is left
     * out and logged.
     */
    fun load(
        sampleRate: Int,
        channels: Int,
        installed: List<String> = emptyList(),
    ): List<PluginSpec> {
        val bundled =
            FILES.map { name ->
                name to
                    BundledPlugins::class.java
                        .getResourceAsStream("/plugins/$name")
                        ?.bufferedReader()
                        ?.readText()
            }
        return (bundled + installed.mapIndexed { i, source -> "installed[$i]" to source }).mapNotNull { (name, source) ->
            if (source == null) {
                Log.w(TAG, "$name is missing from the build")
                return@mapNotNull null
            }
            when (val result = PluginLoader().load(source, sampleRate, channels)) {
                is PluginLoader.Result.Loaded -> {
                    result.plugin
                }

                is PluginLoader.Result.Rejected -> {
                    result.errors.forEach { Log.w(TAG, "$name was refused: ${it.code}: ${it.message}") }
                    null
                }
            }
        }
    }

    /**
     * What the rack shows for a plug-in: its name, and a control for every
     * parameter it declared.
     */
    fun describe(plugin: PluginSpec) =
        EffectSpec(
            id = plugin.id,
            name = plugin.name,
            description = plugin.about,
            params =
                plugin.params.map {
                    ParamSpec(
                        id = it.id,
                        name = it.name.replaceFirstChar(Char::uppercase),
                        min = it.min,
                        max = it.max,
                        default = it.default,
                        help = it.help,
                        unit = it.unit,
                        toggle = it.toggle,
                        choices = it.choices.takeIf { named -> named.isNotEmpty() },
                        displayScale = it.displayScale,
                        displayDecimals = it.displayDecimals,
                        displayZero = it.displayZero,
                    )
                },
            ui = plugin.ui,
            presets = plugin.presets,
        )

    /**
     * A graph made runnable.
     *
     * The node rates are filled in here by [GraphCompiler.annotate], so
     * whoever builds a graph does not state them. A graph that does not
     * compile is logged, and null is returned.
     */
    fun engine(graph: GraphSpec): FrameProcessor? {
        val annotated = GraphCompiler.annotate(graph)
        return when (val compiled = GraphCompiler.compile(annotated)) {
            is GraphCompiler.Result.Compiled -> {
                GraphCompiler.engine(annotated)
            }

            is GraphCompiler.Result.Failed -> {
                compiled.errors.forEach { Log.w(TAG, "a graph would not compile: ${it.code}: ${it.message}") }
                null
            }
        }
    }
}
