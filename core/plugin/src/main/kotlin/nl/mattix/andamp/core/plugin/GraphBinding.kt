// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import nl.mattix.andamp.core.dsp.ConstArg
import nl.mattix.andamp.core.dsp.Edge
import nl.mattix.andamp.core.dsp.GraphParam
import nl.mattix.andamp.core.dsp.GraphSpec
import nl.mattix.andamp.core.dsp.NodeSpec
import nl.mattix.andamp.core.dsp.Primitive
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.VarArgFunction

/**
 * The `g` a plug-in's `build` is handed.
 *
 * Every call appends a node and returns a handle, so a script describes a graph and computes
 * no audio. The Lua has finished before a frame is processed.
 *
 * A handle is a table holding a node index and not a bare number, because a number already
 * means a constant: `g.mul(x, 2)` and `g.mul(x, someNode)` must be told apart.
 */
internal class GraphBinding(
    private val channels: Int,
    private val sampleRate: Int,
    private val params: List<GraphParam>,
) {
    private val nodes = mutableListOf<NodeSpec>()

    /** One node index per declared parameter, created the first time it is used. */
    private val paramNodes = HashMap<Int, Int>()

    fun table(): LuaTable {
        val g = LuaTable()
        g.set("input", function { args -> handle(node(Primitive.INPUT, consts = mapOf("ch" to num(args.checkint(1))))) })
        binary(g, "add", Primitive.ADD)
        binary(g, "sub", Primitive.SUB)
        binary(g, "mul", Primitive.MUL)
        binary(g, "div", Primitive.DIV)
        binary(g, "min", Primitive.MIN)
        binary(g, "max", Primitive.MAX)
        g.set("sub", function { args -> handle(node(Primitive.SUB, mapOf("a" to edge(args.arg(1)), "b" to edge(args.arg(2))))) })
        g.set(
            "crossfade",
            function { args ->
                handle(
                    node(
                        Primitive.CROSSFADE,
                        mapOf("a" to edge(args.arg(1)), "b" to edge(args.arg(2)), "t" to edge(args.arg(3))),
                    ),
                )
            },
        )
        g.set("sanitise", function { args -> handle(node(Primitive.SANITISE, mapOf("input" to edge(args.arg(1))))) })
        g.set("clip", function { args -> options(args.checktable(1), Primitive.CLIP) })
        g.set("math", function { args -> options(args.checktable(1), Primitive.MATH) })
        g.set("gain", function { args -> options(args.checktable(1), Primitive.GAIN) })
        g.set("biquad", function { args -> options(args.checktable(1), Primitive.BIQUAD) })
        g.set("onepole", function { args -> options(args.checktable(1), Primitive.ONEPOLE) })
        g.set("allpass1", function { args -> options(args.checktable(1), Primitive.ALLPASS1) })
        g.set("lfo", function { args -> options(args.checktable(1), Primitive.LFO) })
        g.set("envelope", function { args -> options(args.checktable(1), Primitive.ENVELOPE) })
        // a table that may be empty: g.noise {} is noise with the default seed
        g.set("noise", function { args -> options(if (args.istable(1)) args.checktable(1) else LuaTable(), Primitive.NOISE) })
        g.set("softclip", function { args -> options(args.checktable(1), Primitive.SOFTCLIP) })
        g.set("delay", function { args -> options(args.checktable(1), Primitive.DELAY) })
        g.set("tap", function { args -> options(args.checktable(1), Primitive.TAP) })
        g.set(
            "tapOut",
            function { args -> handle(node(Primitive.TAPOUT, consts = mapOf("name" to text(args.checkjstring(1))))) },
        )
        g.set(
            "tapIn",
            function { args ->
                node(
                    Primitive.TAPIN,
                    edges = mapOf("source" to edge(args.arg(2))),
                    consts = mapOf("name" to text(args.checkjstring(1))),
                )
                LuaValue.NIL
            },
        )
        // an unknown primitive raises an error that names it, where a plain table would
        // fail later as a call on nil
        val absent = LuaTable()
        absent.set(
            "__index",
            function { args ->
                val wanted = args.arg(2).tojstring()
                throw LuaError("there is no primitive called '$wanted'")
            },
        )
        g.setmetatable(absent)
        return g
    }

    fun spec(outputs: LuaValue): GraphSpec {
        val perChannel =
            (0 until channels).map {
                val at = outputs.get(it)
                if (at.isnil()) throw LuaError("build returned nothing for channel $it")
                index(at)
            }
        val delayFrames =
            nodes.sumOf { ((it.consts["maxTime"] as? ConstArg.Num)?.value ?: 0f).toInt() }
        return GraphSpec(
            sampleRate = sampleRate,
            channels = channels,
            params = params,
            nodes = nodes.toList(),
            outputs = perChannel,
            delayFrames = delayFrames,
        )
    }

    private fun binary(
        g: LuaTable,
        name: String,
        primitive: Primitive,
    ) = g.set(
        name,
        function { args ->
            // varargs fold left to right, so summing five terms is one call
            var acc = edge(args.arg(1))
            for (i in 2..args.narg()) {
                acc = Edge.Ref(node(primitive, mapOf("a" to acc, "b" to edge(args.arg(i)))))
            }
            handle(index(acc))
        },
    )

    /** A primitive called with one table of named arguments. */
    private fun options(
        table: LuaTable,
        primitive: Primitive,
    ): LuaValue {
        val edges = mutableMapOf<String, Edge>()
        val consts = mutableMapOf<String, ConstArg>()
        primitive.keys.forEach { key ->
            val value = table.get(key)
            if (value.isnil()) return@forEach
            if (key in primitive.constKeys) {
                consts[key] =
                    when {
                        primitive == Primitive.TAP && key == LINE -> num(line(value))
                        primitive.isText(key) -> text(value.checkjstring())
                        else -> num(value.checkdouble())
                    }
            } else {
                edges[key] = edge(value)
            }
        }
        // an unknown key is recorded as a constant, which the validator reports by name
        table.keys().forEach { key ->
            val name = key.tojstring()
            if (name !in primitive.keys) {
                consts[name] = ConstArg.Text("")
            }
        }
        return handle(node(primitive, edges, consts))
    }

    private fun node(
        primitive: Primitive,
        edges: Map<String, Edge> = emptyMap(),
        consts: Map<String, ConstArg> = emptyMap(),
    ): Int {
        nodes += NodeSpec(primitive, edges, consts)
        return nodes.size - 1
    }

    /** A number is a constant, a handle is a node, and a parameter is a node made on first use. */
    private fun edge(value: LuaValue): Edge =
        when {
            value.isnumber() -> Edge.Const(value.todouble().toFloat())
            value.istable() && !value.get(PARAM).isnil() -> Edge.Ref(paramNode(value.get(PARAM).toint()))
            value.istable() && !value.get(NODE).isnil() -> Edge.Ref(value.get(NODE).toint())
            else -> throw LuaError("expected a number, a parameter or something built by g")
        }

    /**
     * The line a tap reads: the handle `g.delay` returned, stored as that node's index. A
     * plain number is refused.
     */
    private fun line(value: LuaValue): Int =
        value
            .takeIf { it.istable() && it.get(PARAM).isnil() }
            ?.get(NODE)
            ?.takeIf { !it.isnil() }
            ?.toint()
            ?: throw LuaError("a tap's line is what g.delay returned")

    private fun paramNode(index: Int): Int =
        paramNodes.getOrPut(index) { node(Primitive.PARAM, consts = mapOf("index" to num(index))) }

    private fun index(edge: Edge): Int = (edge as? Edge.Ref)?.node ?: throw LuaError("a constant cannot be an output")

    private fun index(value: LuaValue): Int = index(edge(value))

    private fun handle(node: Int): LuaValue = LuaTable().also { it.set(NODE, node) }

    private fun num(value: Number) = ConstArg.Num(value.toFloat())

    private fun text(value: String) = ConstArg.Text(value)

    private fun function(body: (org.luaj.vm2.Varargs) -> LuaValue) =
        object : VarArgFunction() {
            override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs = body(args)
        }

    private companion object {
        const val NODE = "node"
        const val PARAM = "param"
        const val LINE = "line"
    }
}
