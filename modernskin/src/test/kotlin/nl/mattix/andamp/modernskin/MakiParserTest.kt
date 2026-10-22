// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.MakiHost
import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.MakiParser
import nl.mattix.andamp.modernskin.maki.Opcodes
import nl.mattix.andamp.modernskin.maki.Script
import nl.mattix.andamp.modernskin.maki.StdApi
import nl.mattix.andamp.modernskin.maki.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MakiParserTest {
    private fun resource(name: String): ByteArray = localResource("/$name").openStream().use { it.readBytes() }

    private fun describe(name: String) {
        val p = MakiParser.parse(resource(name), name)
        println(
            "== $name v${p.version}: ${p.classes.size} classes, ${p.methods.size} methods, " +
                "${p.variables.size} vars, ${p.bindings.size} bindings, ${p.code.size} ops",
        )
        println("classes: " + p.classes.joinToString { StdApi.byGuid[it]?.name ?: "?$it" })
        val unknownMethods = p.methods.filter { StdApi.method(p.classes[it.classIndex], it.name) == null }
        println("unknown methods: " + unknownMethods.joinToString { "${p.className(it.classIndex)}.${it.name}" })
        println(
            "bindings: " +
                p.bindings.joinToString {
                    "${p.variables[it.variable].kind}#${it.variable}.${p.methods[it.method].name}@${it.codeIndex}"
                },
        )
        val opcodes =
            p.code
                .groupingBy { it.opcode }
                .eachCount()
                .toSortedMap()
        println("opcodes: " + opcodes.entries.joinToString { "0x${it.key.toString(16)}=${it.value}" })
        val known =
            Opcodes::class.java.declaredFields
                .filter { it.type == Int::class.java }
                .map { it.getInt(null) }
                .toSet()
        val odd = opcodes.keys.filter { it !in known }
        assertTrue("odd opcodes $odd", odd.isEmpty())
    }

    @Test
    fun parsesSampleScripts() {
        describe("corner/corner.maki")
        describe("corner/standardframe.maki")
        describe("main.maki")
        describe("hide.maki")
        describe("center.maki")
    }

    /** A System that records calls and answers with plausible values, to see the prologue run. */
    private class FakeSystem : MakiObject {
        override val makiGuid = StdApi.guidOf("System")!!
        val calls = ArrayList<String>()

        override fun makiCall(
            name: String,
            args: List<Value>,
        ): Value? {
            calls.add("$name(${args.joinToString()})")
            return when (name) {
                "getcontainer", "getscriptgroup", "newgroup" -> Value.Null
                "getparam" -> Value.Str("5,15,-10,-36,0,0,1,1")
                "gettoken" -> Value.Str(args[0].asString.split(args[1].asString).getOrElse(args[2].asInt) { "" })
                "messagebox" -> Value.Int(0)
                "getruntimeversion" -> Value.Num(1.36)
                "getskinname" -> Value.Str("test")
                "integertostring" -> Value.Str(args[0].asInt.toString())
                "getprivateint" -> Value.Int(args[2].asInt)
                "getvolume" -> Value.Int(128)
                "getleftvumeter", "getrightvumeter" -> Value.Int(0)
                else -> Value.Int(0)
            }
        }
    }

    @Test
    fun runsStandardFrameOnScriptLoaded() {
        val p = MakiParser.parse(resource("corner/standardframe.maki"), "standardframe")
        val system = FakeSystem()
        val logs = ArrayList<String>()
        val host =
            object : MakiHost {
                override fun create(guid: String): MakiObject? = null

                override fun log(message: String) {
                    logs.add(message)
                }
            }
        val script = Script(p, system, host)
        val ran = script.dispatch(system, "onscriptloaded", emptyList())
        println("calls: ${system.calls}")
        println("logs: $logs")
        assertEquals(1, ran)
        assertTrue(system.calls.any { it.startsWith("getscriptgroup") })
        assertTrue(system.calls.any { it.startsWith("gettoken") })
        // after the prologue the x..rh strings hold the tokens of the param
        val strings = script.cells.filter { it.kind == nl.mattix.andamp.modernskin.maki.Kind.STRING }.map { it.value.asString }
        println("strings: $strings")
        assertTrue(strings.contains("-36"))
    }
}
