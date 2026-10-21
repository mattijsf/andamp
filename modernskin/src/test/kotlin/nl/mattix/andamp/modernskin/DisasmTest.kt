// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.MakiParser
import nl.mattix.andamp.modernskin.maki.Opcodes
import org.junit.Test

/** Prints the compiler's prologue of a script, to see what the version check compares. */
class DisasmTest {
    @Test
    fun prologue() {
        val bytes =
            System.getProperty("disasm.file")?.let { java.io.File(it).readBytes() }
                ?: localResource("/corner/corner.maki").openStream().use { it.readBytes() }
        val p = MakiParser.parse(bytes, "corner")
        val names =
            Opcodes::class.java.declaredFields.filter { it.type == Int::class.java }.associate {
                it.getInt(null) to
                    it.name
            }
        p.variables.take(20).forEachIndexed {
            i,
            v,
            ->
            println("var $i ${v.kind} ${v.guid?.let { p.classes.indexOf(it) } ?: ""} init=${v.constant ?: v.initial}")
        }
        val from = System.getProperty("disasm.from")?.toInt() ?: 0
        val to = System.getProperty("disasm.to")?.toInt() ?: 95
        p.bindings.forEach { println("binding var${it.variable} ${p.methods[it.method].name} -> ${it.codeIndex}") }
        for (i in from until minOf(to, p.code.size)) {
            val op = p.code[i]
            val extra =
                when (op.opcode) {
                    Opcodes.PUSH, Opcodes.POP_TO -> {
                        " var${op.arg}=" +
                            (p.variables[op.arg].constant ?: p.variables[op.arg].initial) +
                            " " +
                            p.variables[op.arg].kind
                    }

                    Opcodes.CALL, Opcodes.CALL2 -> {
                        " " + p.className(p.methods[op.arg].classIndex) + "." + p.methods[op.arg].name +
                            " argc=${op.argc}"
                    }

                    Opcodes.NEW -> {
                        " " + p.className(op.arg)
                    }

                    in Opcodes.jumpOps -> {
                        " -> ${op.arg}"
                    }

                    else -> {
                        ""
                    }
                }
            println("$i @${op.offset}: ${names[op.opcode] ?: op.opcode}$extra")
        }
    }
}
