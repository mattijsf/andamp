// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.MakiHost
import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.MakiParser
import nl.mattix.andamp.modernskin.maki.Opcodes
import nl.mattix.andamp.modernskin.maki.Script
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SkinsFolderFiles
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A script can keep a variable on each object of a class it declares: `EQ_p12.setTo = 127` in
 * Big Bento's equalizer. Opcode 0x68 pops the member's name and the object and leaves the member's
 * slot; its four-byte immediate is the member's type (2, an int, there). Read as an opcode without
 * an immediate, those four bytes become a POP and three opcode 0s, and the handler stops.
 */
class MakiMemberTest {
    private class Thing : MakiObject {
        override val makiGuid = ""

        override fun makiCall(
            name: String,
            args: List<Value>,
        ): Value? = null
    }

    private val logs = ArrayList<String>()
    private val host =
        object : MakiHost {
            override fun create(guid: String): MakiObject? = null

            override fun log(message: String) {
                logs += message
            }
        }

    /**
     * A `.maki` file: var0 a class variable, var1 and var2 objects of that class, var3 "setTo",
     * var4 127, var5 an int target, var6 0. Code: onScriptLoaded sets var1.setTo to 127 and
     * var2.setTo to -127; the class's onLeftClick copies the clicked object's setTo into var5.
     */
    private fun program(): ByteArray {
        // var1.setTo = 127; var2.setTo = -127;
        val onScriptLoaded =
            instructions(
                Opcodes.PUSH to 1,
                Opcodes.PUSH to 3,
                Opcodes.MEMBER to INT,
                Opcodes.PUSH to 4,
                Opcodes.MOV to null,
                Opcodes.POP to null,
                Opcodes.PUSH to 2,
                Opcodes.PUSH to 3,
                Opcodes.MEMBER to INT,
                Opcodes.PUSH to 4,
                Opcodes.NEG to null,
                Opcodes.MOV to null,
                Opcodes.POP to null,
                Opcodes.PUSH to 6,
                Opcodes.RETURN to null,
            )
        // var5 = var0.setTo;
        val onLeftClick =
            instructions(
                Opcodes.PUSH to 5,
                Opcodes.PUSH to 0,
                Opcodes.PUSH to 3,
                Opcodes.MEMBER to INT,
                Opcodes.MOV to null,
                Opcodes.POP to null,
                Opcodes.PUSH to 6,
                Opcodes.RETURN to null,
            )
        return Maki()
            .apply {
                classes(1)
                methods("onleftclick")
                variables(
                    objectVar(classIndex = 0, global = false),
                    memberVar(classVar = 0),
                    memberVar(classVar = 0),
                    primitiveVar(STRING),
                    primitiveVar(INT, 127),
                    primitiveVar(INT),
                    primitiveVar(INT),
                )
                constants(3 to "setTo")
                bindings(Triple(0, 0, onScriptLoaded.size))
                code(onScriptLoaded + onLeftClick)
            }.bytes()
    }

    @Test
    fun `the member opcode's immediate is read with it`() {
        val p = MakiParser.parse(program(), "test")

        assertEquals("no opcode 0 from a misread immediate", 0, p.code.count { it.opcode == 0 })
        assertEquals(3, p.code.count { it.opcode == Opcodes.MEMBER })
        assertEquals(23, p.code.size)
    }

    @Test
    fun `each object of the class holds a separate member`() {
        val a = Thing()
        val b = Thing()
        val s = Script(MakiParser.parse(program(), "test"), Thing(), host)
        s.cells[1].assign(Value.Obj(a))
        s.cells[2].assign(Value.Obj(b))

        s.run(0, emptyList(), "onscriptloaded")
        s.dispatch(b, "onleftclick", emptyList())

        assertTrue("nothing failed: $logs", logs.isEmpty())
        assertEquals(-127, s.cells[5].value.asInt)
        s.dispatch(a, "onleftclick", emptyList())
        assertEquals(127, s.cells[5].value.asInt)
    }

    @Test
    fun `Big Bento's equalizer script runs its onScriptLoaded to the end`() {
        val bento = File(localResource("/Bento.wal").toURI())
        val bigBento = File(localResource("/BigBento.wal").toURI())
        val files = SkinsFolderFiles(ZipSkinFiles(bento), mapOf("bigbento" to ZipSkinFiles(bigBento)))
        val skinHost = TestHost(files)

        SkinRuntime(files, skinHost, FakePlayer()).load()

        val eq = skinHost.log.filter { "eq.maki" in it && "stopped" in it }
        assertTrue("eq.maki ran without stopping: $eq", eq.isEmpty())
    }

    /** Instructions as bytes: each opcode, then its four-byte immediate where it has one. */
    private fun instructions(vararg ops: Pair<Int, Int?>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((opcode, immediate) in ops) {
            out.write(opcode)
            immediate?.let { out.write(le(it)) }
        }
        return out.toByteArray()
    }

    /** The sections of a `.maki` file in order, as MakiParser reads them; version 23 has a system flag per variable. */
    private class Maki {
        private val out = ByteArrayOutputStream()

        init {
            out.write("FG".toByteArray())
            short(1027)
            int(23)
        }

        fun classes(n: Int) {
            int(n)
            repeat(n) { out.write(ByteArray(16) { i -> (i + 1).toByte() }) }
        }

        fun methods(vararg names: String) {
            int(names.size)
            for (n in names) {
                short(0)
                short(0)
                string(n)
            }
        }

        fun variables(vararg vars: ByteArray) {
            int(vars.size)
            vars.forEach { out.write(it) }
        }

        fun constants(vararg c: Pair<Int, String>) {
            int(c.size)
            for ((v, s) in c) {
                int(v)
                string(s)
            }
        }

        fun bindings(vararg b: Triple<Int, Int, Int>) {
            int(b.size)
            for ((v, m, off) in b) {
                int(v)
                int(m)
                int(off)
            }
        }

        fun code(bytes: ByteArray) {
            int(bytes.size)
            out.write(bytes)
        }

        fun bytes(): ByteArray = out.toByteArray()

        private fun short(v: Int) = out.write(le(v), 0, 2)

        private fun int(v: Int) = out.write(le(v))

        private fun string(s: String) {
            short(s.length)
            out.write(s.toByteArray(Charsets.ISO_8859_1))
        }
    }

    private companion object {
        const val INT = 2
        const val STRING = 6

        fun le(v: Int): ByteArray =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(v)
                .array()

        fun flag(b: Boolean): Byte = if (b) 1 else 0

        /** One entry of the variables table: type, object flag, subclass flag, initial value, global and system flags. */
        fun variable(
            type: Int,
            isObject: Boolean,
            subclass: Boolean,
            initial: Int,
            global: Boolean,
        ): ByteArray =
            ByteBuffer
                .allocate(14)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(type.toByte())
                .put(flag(isObject))
                .putShort(flag(subclass).toShort())
                .putShort((initial and 0xffff).toShort())
                .putShort((initial ushr 16).toShort())
                .putShort(0)
                .putShort(0)
                .put(flag(global))
                .put(0.toByte())
                .array()

        fun objectVar(
            classIndex: Int,
            global: Boolean,
        ) = variable(classIndex, isObject = true, subclass = false, initial = 0, global = global)

        fun memberVar(classVar: Int) = variable(classVar, isObject = false, subclass = true, initial = 0, global = true)

        fun primitiveVar(
            type: Int,
            initial: Int = 0,
        ) = variable(type, isObject = false, subclass = false, initial = initial, global = false)
    }
}
