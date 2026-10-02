// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import nl.mattix.andamp.core.dsp.GraphParam
import nl.mattix.andamp.core.model.Preset
import nl.mattix.andamp.core.model.UiNode
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.VarArgFunction

/**
 * `ui { }`: the layout a plug-in asks for.
 *
 * Five constructors and three options, as in section 3 of docs/dsp-plugin-spec.md. An unknown
 * option is refused, and so is a widget given the wrong kind of parameter.
 *
 * A widget names its parameter by the handle `param.*` returned, not by its id. The graph
 * binding reads the same handle.
 */
internal class UiBinding(
    private val params: List<GraphParam>,
) {
    /** What `ui { }` was given, or empty when it was never called. */
    var tree: List<UiNode> = emptyList()
        private set

    /** What `presets { }` was given, or empty when it was never called. */
    var presets: List<Preset> = emptyList()
        private set

    fun install(globals: org.luaj.vm2.Globals) {
        globals.set("ui", function { args -> collect(args.checktable(1)) })
        globals.set("slider", widget(UiNode.Kind.SLIDER))
        globals.set("toggle", widget(UiNode.Kind.TOGGLE))
        globals.set("choice", widget(UiNode.Kind.CHOICE))
        globals.set("label", function { args -> label(args) })
        globals.set("group", function { args -> group(args) })
        globals.set("presets", function { args -> collectPresets(args.checktable(1)) })
    }

    private fun collect(table: LuaTable): LuaValue {
        tree = items(table)
        return LuaValue.NIL
    }

    /**
     * `presets { }`: named settings.
     *
     * A preset names parameters by id, so that it stays valid when a plug-in reorders its
     * controls. One that names a parameter the plug-in does not have is refused.
     */
    private fun collectPresets(table: LuaTable): LuaValue {
        presets =
            (1..table.length()).map { at ->
                val entry = table.get(at).checktable()
                val name = entry.get("name").checkjstring()
                val values = entry.get("values").checktable()
                Preset(
                    name = name,
                    values =
                        values.keys().associate { key ->
                            val id = key.tojstring()
                            val param =
                                params.firstOrNull { it.id == id }
                                    ?: throw LuaError("the preset '$name' sets '$id', which is not one of its controls")
                            id to value(values.get(key), param, name)
                        },
                )
            }
        return LuaValue.NIL
    }

    /** A preset value is a number, or a boolean for a toggle. */
    private fun value(
        raw: LuaValue,
        param: GraphParam,
        preset: String,
    ): Float =
        when {
            raw.isboolean() -> if (raw.checkboolean()) 1f else 0f
            raw.isnumber() -> raw.checkdouble().toFloat()
            else -> throw LuaError("the preset '$preset' sets '${param.id}' to something that is not a value")
        }

    /** A widget for one parameter, refused when the parameter is not the kind the widget draws. */
    private fun widget(kind: UiNode.Kind) =
        function { args ->
            val index = handle(args.arg(1), kind)
            val opts = args.arg(2)
            val (label, help) = options(opts, allow = setOf(LABEL, HELP), what = kind.name.lowercase())
            node(UiNode.Control(index, kind, label, help))
        }

    private fun label(args: org.luaj.vm2.Varargs): LuaValue {
        val text = args.arg(1).checkjstring()
        // a label takes no help option
        val (override, _) = options(args.arg(2), allow = setOf(LABEL), what = "label")
        return node(UiNode.Label(override ?: text))
    }

    private fun group(args: org.luaj.vm2.Varargs): LuaValue {
        val name = args.arg(1).takeIf { !it.isnil() }?.checkjstring()
        val items = items(args.arg(2).checktable())
        val opts = args.arg(3)
        val (label, help) = options(opts, allow = setOf(LABEL, HELP, ENABLED_BY), what = "group")
        val by =
            opts
                .takeIf { !it.isnil() }
                ?.get(ENABLED_BY)
                ?.takeIf { !it.isnil() }
                ?.let { handle(it, UiNode.Kind.TOGGLE, why = "enabledBy") }
        return node(UiNode.Group(label ?: name, items, by, help))
    }

    /**
     * Nodes are stored as they are built and looked up through the table Lua hands over,
     * because the constructors run inside out: `group(a, { slider(x) })` runs the slider
     * first.
     */
    private val built = mutableMapOf<Int, UiNode>()
    private var next = 0

    private fun node(node: UiNode): LuaValue {
        built[next] = node
        return LuaTable().also { it.set(WIDGET, next++) }
    }

    private fun items(table: LuaTable): List<UiNode> =
        (1..table.length()).map { at ->
            val handle = table.get(at)
            val id = handle.get(WIDGET)
            if (id.isnil()) throw LuaError("a ui block may only hold widgets, and item $at is not one")
            built.getValue(id.checkint())
        }

    /** The parameter index behind a handle, checked against what the widget can draw. */
    private fun handle(
        value: LuaValue,
        kind: UiNode.Kind,
        why: String = kind.name.lowercase(),
    ): Int {
        val at = value.get(PARAM)
        val index = if (at.isnil()) NONE else at.checkint()
        val param = params.getOrNull(index) ?: throw LuaError("$why needs a parameter, and was given something else")
        if (!draws(kind, param)) throw LuaError("$why cannot draw '${param.id}', which is ${describe(param)}")
        return index
    }

    /** Whether a widget of [kind] can draw [param]. */
    private fun draws(
        kind: UiNode.Kind,
        param: GraphParam,
    ) = when (kind) {
        UiNode.Kind.SLIDER -> !param.toggle && param.choices.isEmpty()
        UiNode.Kind.TOGGLE -> param.toggle
        UiNode.Kind.CHOICE -> param.choices.isNotEmpty()
    }

    private fun describe(param: GraphParam) =
        when {
            param.toggle -> "a toggle"
            param.choices.isNotEmpty() -> "a choice"
            else -> "a number"
        }

    /** Reads an options table and refuses any key not in [allow]. Returns the label and the help. */
    private fun options(
        opts: LuaValue,
        allow: Set<String>,
        what: String,
    ): Pair<String?, String?> {
        if (opts.isnil()) return null to null
        val table = opts.checktable()
        table.keys().forEach { key ->
            val name = key.tojstring()
            if (name !in allow) throw LuaError("$what has no option '$name'")
        }
        return table.get(LABEL).takeIf { !it.isnil() }?.checkjstring() to
            table.get(HELP).takeIf { !it.isnil() }?.checkjstring()
    }

    private fun function(body: (org.luaj.vm2.Varargs) -> LuaValue) =
        object : VarArgFunction() {
            override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs = body(args)
        }

    private companion object {
        const val PARAM = "param"
        const val WIDGET = "widget"
        const val LABEL = "label"
        const val HELP = "help"
        const val ENABLED_BY = "enabledBy"

        /** Stands for no parameter: an index that is never valid. */
        const val NONE = -1
    }
}
