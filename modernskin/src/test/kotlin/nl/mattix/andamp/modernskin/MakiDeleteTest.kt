// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Kind
import nl.mattix.andamp.modernskin.maki.MakiHost
import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.MakiProgram
import nl.mattix.andamp.modernskin.maki.Op
import nl.mattix.andamp.modernskin.maki.Opcodes
import nl.mattix.andamp.modernskin.maki.Script
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.maki.VarDecl
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `delete x;` compiles to PUSH x, DELETE, POP: the compiler treats it as an expression and drops its
 * value. The interpreter has to leave a value for that POP, or a handler stops with a stack
 * underflow, or a function quietly eats its caller's operand.
 */
class MakiDeleteTest {
    private class Thing : MakiObject {
        override val makiGuid = ""
        var deleted = false

        override fun makiCall(
            name: String,
            args: List<Value>,
        ): Value? = null

        override fun delete() {
            deleted = true
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

    private fun variable(
        kind: Kind,
        initial: Value,
    ) = VarDecl(kind, guid = null, classVar = null, global = true, initial = initial)

    /** var0 System, var1 the object to delete, var2 0, var3 an assignment target, var4 "done". */
    private fun script(
        thing: Thing,
        vararg code: Pair<Int, Int>,
    ): Script {
        val program =
            MakiProgram(
                id = "test",
                version = 0,
                classes = emptyList(),
                methods = emptyList(),
                variables =
                    listOf(
                        variable(Kind.OBJECT, Value.Null),
                        variable(Kind.OBJECT, Value.Obj(thing)),
                        variable(Kind.INT, Value.Int(0)),
                        variable(Kind.STRING, Value.Str("")),
                        variable(Kind.STRING, Value.Str("done")),
                    ),
                bindings = emptyList(),
                code = code.mapIndexed { i, (op, arg) -> Op(op, arg, -1, i) },
            )
        return Script(program, Thing(), host)
    }

    @Test
    fun `a delete statement leaves the handler running`() {
        val thing = Thing()
        val s =
            script(
                thing,
                Opcodes.PUSH to 1,
                Opcodes.DELETE to 0,
                Opcodes.POP to 0,
                Opcodes.PUSH to 2,
                Opcodes.RETURN to 0,
            )

        s.run(0, emptyList(), "onscriptloaded")

        assertTrue("the object was told", thing.deleted)
        assertFalse("the script still runs: $logs", s.disabled)
        assertTrue("nothing failed: $logs", logs.isEmpty())
    }

    @Test
    fun `a delete inside a function keeps the caller's operand`() {
        // target = f(); with f() { delete thing; return "done"; }
        val thing = Thing()
        val s =
            script(
                thing,
                Opcodes.PUSH to 3,
                Opcodes.CALL_GLOBAL to 6,
                Opcodes.MOV to 0,
                Opcodes.POP to 0,
                Opcodes.PUSH to 2,
                Opcodes.RETURN to 0,
                Opcodes.PUSH to 1,
                Opcodes.DELETE to 0,
                Opcodes.POP to 0,
                Opcodes.PUSH to 4,
                Opcodes.RETURN to 0,
            )

        s.run(0, emptyList(), "ontitlechange")

        assertTrue("nothing failed: $logs", logs.isEmpty())
        assertEquals("the function's result reached its target", "done", s.cells[3].value.asString)
    }

    @Test
    fun `a deleted timer stops firing`() {
        val files =
            object : SkinFiles {
                override fun read(path: String): ByteArray? = null

                override fun names(): List<String> = emptyList()
            }
        val skinHost =
            object : SkinHost {
                override fun imageSize(file: String): Pair<Int, Int>? = null

                override fun textWidth(
                    text: String,
                    font: FontDef?,
                    size: Int,
                ) = 0

                override fun log(message: String) = Unit

                override fun invalidate() = Unit
            }
        val rt = SkinRuntime(files, skinHost, FakePlayer())
        val timer = Timer(rt).apply { delay = 10 }
        timer.start()
        assertTrue(timer in rt.timers)

        timer.delete()

        assertFalse(timer.running)
        assertFalse(timer in rt.timers)
    }
}
