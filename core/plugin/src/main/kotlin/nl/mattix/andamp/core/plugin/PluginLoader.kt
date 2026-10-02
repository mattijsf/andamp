// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import nl.mattix.andamp.core.dsp.GraphCompiler
import nl.mattix.andamp.core.dsp.GraphParam
import nl.mattix.andamp.core.dsp.GraphValidator
import nl.mattix.andamp.core.model.UiNode
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.VarArgFunction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a plug-in's Lua once and keeps only what it described.
 *
 * The script runs in [Sandbox]'s environment. The source has a size limit, checked before
 * anything is parsed. The load has a deadline: the host stops waiting for the worker, and the
 * interpreter's instruction hook then stops the script, so a script that loops forever does not
 * have to cooperate.
 *
 * A rejected plug-in does not appear. None of this runs on the audio thread.
 */
class PluginLoader(
    private val budgets: Budgets = Budgets(),
) {
    data class Budgets(
        val maxSourceBytes: Int = 256 * 1024,
        /**
         * How long a plug-in may take to load before the host gives up on it. The deadline
         * exists to abandon a script that hangs, so it is generous: loading is rare and
         * happens on a worker thread.
         */
        val maxLoadMillis: Long = 3_000,
    )

    sealed interface Result {
        data class Loaded(
            val plugin: PluginSpec,
            /** How long the load took, in milliseconds. */
            val millis: Long = 0,
        ) : Result

        data class Rejected(
            val errors: List<PluginError>,
        ) : Result
    }

    fun load(
        source: String,
        sampleRate: Int,
        channels: Int,
    ): Result {
        val started = System.nanoTime()
        if (source.toByteArray().size > budgets.maxSourceBytes) {
            return Result.Rejected(listOf(PluginError.TooLong(source.toByteArray().size, budgets.maxSourceBytes)))
        }
        // a worker thread the host can stop waiting for
        val worker = Executors.newSingleThreadExecutor { Thread(it, "plugin-load").apply { isDaemon = true } }
        val watchdog = Sandbox.Watchdog()
        return try {
            worker
                .submit<Result> { run(source, sampleRate, channels, watchdog) }
                .get(budgets.maxLoadMillis, TimeUnit.MILLISECONDS)
                .let { if (it is Result.Loaded) it.copy(millis = (System.nanoTime() - started) / NANOS_PER_MILLI) else it }
        } catch (expected: java.util.concurrent.TimeoutException) {
            // the watchdog stops the script at its next instruction, so the worker ends
            watchdog.abandoned = true
            Result.Rejected(listOf(PluginError.TooSlow(budgets.maxLoadMillis)))
        } catch (expected: java.util.concurrent.ExecutionException) {
            Result.Rejected(listOf(PluginError.Failed(expected.cause?.message ?: "no reason given")))
        } finally {
            worker.shutdownNow()
        }
    }

    private fun run(
        source: String,
        sampleRate: Int,
        channels: Int,
        watchdog: Sandbox.Watchdog,
    ): Result {
        val globals = Sandbox.globals(watchdog)
        val declared = mutableMapOf<String, LuaValue>()
        val params = mutableListOf<GraphParam>()
        val unread = mutableListOf<String>()
        globals.set("plugin", collect(declared))
        globals.set("param", paramTable(params, unread))
        val ui = UiBinding(params)
        ui.install(globals)
        val chunk =
            try {
                globals.load(source, "plugin")
            } catch (expected: LuaError) {
                return Result.Rejected(listOf(PluginError.Syntax(expected.message ?: "unreadable")))
            }
        return try {
            chunk.call()
            assemble(globals, declared, params, ui, sampleRate, channels)
                .let { if (it is Result.Loaded) it.copy(plugin = it.plugin.copy(unread = unread)) else it }
        } catch (expected: Refusal) {
            Result.Rejected(listOf(expected.error))
        } catch (expected: LuaError) {
            // with a hook installed the interpreter appends a newline and an empty traceback
            // to every message
            Result.Rejected(listOf(PluginError.Failed(expected.message?.trimEnd() ?: "no reason given")))
        }
    }

    /**
     * A declaration refused with a [PluginError] of its own. Thrown from inside the script's
     * call so that the script stops there, and caught as that error.
     */
    private class Refusal(
        val error: PluginError,
    ) : LuaError(error.message)

    @Suppress("LongParameterList") // a plug-in is its metadata, its controls, its layout and its graph
    private fun assemble(
        globals: org.luaj.vm2.Globals,
        declared: Map<String, LuaValue>,
        params: List<GraphParam>,
        ui: UiBinding,
        sampleRate: Int,
        channels: Int,
    ): Result {
        val incomplete = missing(globals, declared)
        if (incomplete.isNotEmpty()) return Result.Rejected(incomplete)
        val binding = GraphBinding(channels, sampleRate, params)
        val context = LuaTable()
        context.set("sampleRate", sampleRate)
        context.set("channels", channels)
        val outputs = globals.get("build").call(binding.table(), context)
        // the script describes what is connected to what; the compiler works out how often
        // each node runs
        val graph = GraphCompiler.annotate(binding.spec(outputs))
        val invalid = GraphValidator.validate(graph)
        val ignored = unreadGates(ui.tree, params, graph) + badUnits(params)
        return if (invalid.isNotEmpty() || ignored.isNotEmpty()) {
            Result.Rejected(invalid.map { PluginError.BadGraph("${it.code}: ${it.message}") } + ignored)
        } else {
            Result.Loaded(describe(declared, params, ui, graph))
        }
    }

    /**
     * Groups whose `enabledBy` toggle the graph never reads.
     *
     * The host cannot bypass a group's part of the graph itself: a group gathers controls,
     * and `build` never mentions groups. So the bypass is written in `build`, and a plug-in
     * whose gate is a toggle nothing reads is refused (docs/dsp-plugin-spec.md section 3).
     */
    private fun unreadGates(
        ui: List<UiNode>,
        params: List<GraphParam>,
        graph: nl.mattix.andamp.core.dsp.GraphSpec,
    ): List<PluginError> {
        val read =
            graph.nodes
                .filter { it.primitive == nl.mattix.andamp.core.dsp.Primitive.PARAM }
                .mapNotNull { (it.consts["index"] as? nl.mattix.andamp.core.dsp.ConstArg.Num)?.value?.toInt() }
                .toSet()
        return groups(ui).mapNotNull { group ->
            val gate = group.enabledBy ?: return@mapNotNull null
            if (gate in read) {
                null
            } else {
                PluginError.UnreadGate(group.name ?: "an untitled group", params[gate].id)
            }
        }
    }

    /** Every group in the tree, nested ones included. */
    private fun groups(ui: List<UiNode>): List<UiNode.Group> =
        ui.filterIsInstance<UiNode.Group>().flatMap { listOf(it) + groups(it.items) }

    /** A number from the parameter's `display` table, or [fallback] when the key is absent. */
    private fun display(
        at: LuaTable,
        key: String,
        fallback: Double,
    ): Double =
        at
            .get("display")
            .takeIf { !it.isnil() }
            ?.get(key)
            ?.optdouble(fallback) ?: fallback

    private fun displayText(
        at: LuaTable,
        key: String,
    ): String =
        at
            .get("display")
            .takeIf { !it.isnil() }
            ?.get(key)
            ?.optjstring("") ?: ""

    /** The required metadata and `build` function that are absent, and a wrong `api`. */
    private fun missing(
        globals: org.luaj.vm2.Globals,
        declared: Map<String, LuaValue>,
    ): List<PluginError> {
        val absent = REQUIRED.filter { declared[it]?.isnil() != false }.map { PluginError.MissingMetadata(it) }
        val build = if (globals.get("build").isfunction()) emptyList() else listOf(PluginError.MissingMetadata("build"))
        // a plug-in that asks for another host version is refused; one that names no
        // version is taken to mean this one
        val asked = declared["api"]?.takeIf { !it.isnil() }?.optint(API) ?: API
        val wrong = if (asked == API) emptyList() else listOf(PluginError.WrongApi(asked, API))
        return absent + build + wrong
    }

    /**
     * Units are LV2 symbols and not free text, so that a host knows how to write them: `hz`
     * renders as Hz and `pc` as a percentage. Any other unit, "Hz" and "%" included, is
     * refused.
     */
    private fun badUnits(params: List<GraphParam>): List<PluginError> =
        params
            .filter { it.unit.isNotEmpty() && it.unit !in UNITS }
            .map { PluginError.UnknownUnit(it.id, it.unit) }

    private fun describe(
        declared: Map<String, LuaValue>,
        params: List<GraphParam>,
        ui: UiBinding,
        graph: nl.mattix.andamp.core.dsp.GraphSpec,
    ) = PluginSpec(
        id = declared.getValue("id").tojstring(),
        name = declared.getValue("name").tojstring(),
        version = declared.getValue("version").tojstring(),
        author = declared["author"]?.tojstring() ?: "",
        about = declared["about"]?.tojstring() ?: "",
        params = params,
        graph = graph,
        ui = ui.tree,
        presets = ui.presets,
    )

    /** `plugin { ... }`: one table of metadata, stored in [into]. */
    private fun collect(into: MutableMap<String, LuaValue>) =
        object : VarArgFunction() {
            override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs {
                val table = args.checktable(1)
                table.keys().forEach { into[it.tojstring()] = table.get(it) }
                return LuaValue.NIL
            }
        }

    /**
     * `param.number { ... }`, `param.toggle { ... }` and `param.choice { ... }`: each declares a
     * parameter and returns the handle it is referred to by.
     *
     * A key the host does not read is ignored and noted in [unread] as `<id>.<key>`.
     */
    private fun paramTable(
        into: MutableList<GraphParam>,
        unread: MutableList<String>,
    ) = LuaTable().also { table ->
        // a toggle is a number: it reaches the graph as 0 or 1, smoothed like any other
        // value (docs/dsp-plugin-spec.md section 2)
        table.set(
            "toggle",
            object : VarArgFunction() {
                override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs {
                    val at = args.checktable(1)
                    unread += unreadKeys(at, TOGGLE_KEYS)
                    if (at.get("default").isnil()) throw Refusal(PluginError.MissingDefault(at.get("id").checkjstring()))
                    into +=
                        GraphParam(
                            id = at.get("id").checkjstring(),
                            min = 0f,
                            max = 1f,
                            default = if (at.get("default").optboolean(false)) 1f else 0f,
                            smoothMs = at.get("smooth").optdouble(GraphParam.DEFAULT_SMOOTH_MS.toDouble()).toFloat(),
                            name = at.get("name").optjstring(at.get("id").checkjstring()),
                            help = at.get("help").optjstring(""),
                            toggle = true,
                        )
                    return LuaTable().also { it.set("param", into.size - 1) }
                }
            },
        )
        // a choice is a number with its settings named: the graph reads the value behind
        // the name that was picked
        table.set(
            "choice",
            object : VarArgFunction() {
                override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs {
                    val at = args.checktable(1)
                    unread += unreadKeys(at, CHOICE_KEYS)
                    val options = at.get("options").checktable()
                    // a Lua table with named keys has no order, so the options are sorted
                    // by value and then by name
                    val choices =
                        options
                            .keys()
                            .map { it.tojstring() to options.get(it).checkdouble().toFloat() }
                            .sortedWith(compareBy({ it.second }, { it.first }))
                            .toMap()
                    if (choices.isEmpty()) {
                        throw LuaError("the choice '${at.get("id").checkjstring()}' has no options")
                    }
                    val default = at.get("default")
                    if (default.isnil()) throw Refusal(PluginError.MissingDefault(at.get("id").checkjstring()))
                    val start =
                        choices[default.checkjstring()]
                            ?: throw LuaError("'${default.tojstring()}' is not one of its options")
                    into +=
                        GraphParam(
                            id = at.get("id").checkjstring(),
                            min = choices.values.min(),
                            max = choices.values.max(),
                            default = start,
                            // not smoothed: the values between two options are other
                            // options
                            smoothMs = 0f,
                            name = at.get("name").optjstring(at.get("id").checkjstring()),
                            help = at.get("help").optjstring(""),
                            choices = choices,
                        )
                    return LuaTable().also { it.set("param", into.size - 1) }
                }
            },
        )
        table.set(
            "number",
            object : VarArgFunction() {
                override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs {
                    val at = args.checktable(1)
                    unread += unreadKeys(at, NUMBER_KEYS)
                    // a default is required and must lie inside the range, so that the
                    // slider is drawn at the value the graph receives
                    val id = at.get("id").checkjstring()
                    if (at.get("default").isnil()) throw Refusal(PluginError.MissingDefault(id))
                    val min = at.get("min").optdouble(0.0).toFloat()
                    val max = at.get("max").optdouble(1.0).toFloat()
                    val default = at.get("default").checkdouble().toFloat()
                    if (default !in min..max) throw Refusal(PluginError.DefaultOutOfRange(id, default, min, max))
                    into +=
                        GraphParam(
                            id = id,
                            min = min,
                            max = max,
                            default = default,
                            smoothMs = at.get("smooth").optdouble(GraphParam.DEFAULT_SMOOTH_MS.toDouble()).toFloat(),
                            name = at.get("name").optjstring(at.get("id").checkjstring()),
                            help = at.get("help").optjstring(""),
                            unit = at.get("unit").optjstring(""),
                            displayScale = display(at, "scale", 1.0).toFloat(),
                            displayDecimals = display(at, "decimals", -1.0).toInt(),
                            displayZero = displayText(at, "zero"),
                        )
                    return LuaTable().also { it.set("param", into.size - 1) }
                }
            },
        )
    }

    private fun unreadKeys(
        at: LuaTable,
        reads: Set<String>,
    ): List<String> {
        val id = at.get("id").optjstring("?")
        return at
            .keys()
            .map { it.tojstring() }
            .filter { it !in reads }
            .map { "$id.$it" }
    }

    private companion object {
        val REQUIRED = listOf("id", "name", "version")

        /** The keys each kind of `param` table is read for; any other key is ignored. */
        val TOGGLE_KEYS = setOf("id", "name", "default", "smooth", "help")
        val CHOICE_KEYS = setOf("id", "name", "options", "default", "help")
        val NUMBER_KEYS =
            setOf("id", "name", "min", "max", "default", "smooth", "help", "unit", "display")

        /** The host version a plug-in's `api` must match. */
        const val API = 1

        /** The LV2 unit symbols the host can render. */
        val UNITS =
            setOf("db", "hz", "khz", "ms", "s", "pc", "semitone12TET", "coef", "degree", "bar")
        const val NANOS_PER_MILLI = 1_000_000
    }
}
