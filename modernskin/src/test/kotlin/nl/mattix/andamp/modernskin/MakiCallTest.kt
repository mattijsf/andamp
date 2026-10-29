// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Binding
import nl.mattix.andamp.modernskin.maki.Kind
import nl.mattix.andamp.modernskin.maki.MakiHost
import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.MakiProgram
import nl.mattix.andamp.modernskin.maki.MethodRef
import nl.mattix.andamp.modernskin.maki.Op
import nl.mattix.andamp.modernskin.maki.Opcodes
import nl.mattix.andamp.modernskin.maki.Script
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.maki.VarDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call whose code carries no argument count takes the count and the return kind from `std.mi`,
 * on every call of the same method, and a method `std.mi` lacks stops the handler every time.
 */
class MakiCallTest {
    private class Thing : MakiObject {
        override val makiGuid = GUI
        val alphas = ArrayList<Int>()

        override fun makiCall(
            name: String,
            args: List<Value>,
        ): Value? =
            when (name) {
                // a float where std.mi says int: the VM hands the script an int
                "getalpha" -> Value.Num(12.7)

                "setalpha" -> Value.Null.also { alphas += args.single().asInt }

                else -> null
            }
    }

    private val logs = ArrayList<String>()
    private val host =
        object : MakiHost {
            override fun create(guid: String): MakiObject? = null

            override fun log(message: String) {
                logs += message
            }
        }

    private val thing = Thing()

    /**
     * var0 System, var1 a GuiObject, var2 the int 200. Handlers: at 0 `return obj.getAlpha()`; at 3
     * `obj.setAlpha(200)`; at 8 `obj.noSuchMethod()`. Calls carry no argument count (-1).
     */
    private fun script(): Script {
        var offset = 0

        fun op(
            code: Int,
            arg: Int = 0,
        ) = Op(code, arg, -1, offset++)
        val program =
            MakiProgram(
                id = "calls",
                version = 22,
                classes = listOf(SYSTEM, GUI),
                methods = listOf(MethodRef(1, "getalpha"), MethodRef(1, "setalpha"), MethodRef(1, "nosuchmethod")),
                variables =
                    listOf(
                        VarDecl(Kind.OBJECT, SYSTEM, null, true, Value.Null),
                        VarDecl(Kind.OBJECT, GUI, null, true, Value.Null),
                        VarDecl(Kind.INT, null, null, true, Value.Int(200)),
                    ),
                bindings = listOf(Binding(0, 0, 0)),
                code =
                    listOf(
                        op(Opcodes.PUSH, 1),
                        op(Opcodes.CALL, 0),
                        op(Opcodes.RETURN),
                        op(Opcodes.PUSH, 1),
                        op(Opcodes.PUSH, 2),
                        op(Opcodes.CALL, 1),
                        op(Opcodes.POP),
                        op(Opcodes.RETURN),
                        op(Opcodes.PUSH, 1),
                        op(Opcodes.CALL, 2),
                        op(Opcodes.RETURN),
                    ),
            )
        return Script(program, thing, host).apply { cells[1].assign(Value.Obj(thing)) }
    }

    @Test
    fun `a call takes its return kind from std_mi each time`() {
        val s = script()
        repeat(3) {
            val alpha = s.run(0, emptyList(), "getAlpha")
            assertTrue(alpha is Value.Int)
            assertEquals(12, (alpha as Value.Int).v)
        }
    }

    @Test
    fun `a call takes its argument count from std_mi each time`() {
        val s = script()
        repeat(3) { s.run(3, emptyList(), "setAlpha") }
        assertEquals(listOf(200, 200, 200), thing.alphas)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `a method std_mi lacks stops the handler each time`() {
        val s = script()
        repeat(2) { s.run(8, emptyList(), "noSuchMethod") }
        assertEquals(2, logs.count { "unknown method GuiObject.nosuchmethod" in it })
    }

    private companion object {
        const val SYSTEM = "d6f50f6493fa49b793f1ba66efae3e98"
        const val GUI = "4ee3e199c6364bec97cd78bc9c8628b0"
    }
}
