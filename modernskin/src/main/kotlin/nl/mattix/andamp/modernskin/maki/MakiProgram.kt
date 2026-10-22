// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.maki

/** A compiled MAKI script, read from a `.maki` file. See MakiParser for the layout. */
class MakiProgram(
    val id: String,
    val version: Int,
    /** Class GUIDs, lowercase hex without dashes, in the order the file declares them. */
    val classes: List<String>,
    val methods: List<MethodRef>,
    val variables: List<VarDecl>,
    val bindings: List<Binding>,
    val code: List<Op>,
) {
    fun className(index: Int): String = StdApi.byGuid[classes[index]]?.name ?: classes[index]
}

class MethodRef(
    val classIndex: Int,
    val name: String,
)

enum class Kind { INT, FLOAT, DOUBLE, BOOLEAN, STRING, OBJECT }

class VarDecl(
    val kind: Kind,
    /** For objects: the class GUID. */
    val guid: String?,
    /** For a variable declared with `Class X Y`: the index of the class variable it belongs to. */
    val classVar: Int?,
    val global: Boolean,
    val initial: Value,
    /** A predeclared global the runtime fills in: System, Config, PlDir and the like. */
    val system: Boolean = false,
) {
    /** A string literal bound to this variable by the constants section. */
    var constant: Value? = null
}

class Binding(
    val variable: Int,
    val method: Int,
    val codeIndex: Int,
)

class Op(
    val opcode: Int,
    /** Variable, method or class index, or a code index for jumps (a byte offset while MakiParser reads the code). */
    val arg: Int,
    /** The argument count carried by a stack-protected call, or -1. */
    val argc: Int,
    /** Byte offset in the code section, for messages. */
    val offset: Int,
)

object Opcodes {
    const val PUSH = 0x01
    const val POP = 0x02
    const val POP_TO = 0x03
    const val EQ = 0x08
    const val NE = 0x09
    const val GT = 0x0a
    const val GE = 0x0b
    const val LT = 0x0c
    const val LE = 0x0d
    const val JUMP_IF_NOT = 0x10
    const val JUMP_IF = 0x11
    const val JUMP = 0x12
    const val CALL = 0x18
    const val CALL_GLOBAL = 0x19
    const val RETURN = 0x21
    const val COMPLETE = 0x28
    const val MOV = 0x30
    const val POST_INC = 0x38
    const val POST_DEC = 0x39
    const val PRE_INC = 0x3a
    const val PRE_DEC = 0x3b
    const val ADD = 0x40
    const val SUB = 0x41
    const val MUL = 0x42
    const val DIV = 0x43
    const val MOD = 0x44
    const val AND = 0x48
    const val OR = 0x49
    const val NOT = 0x4a
    const val BIT_NOT = 0x4b
    const val NEG = 0x4c
    const val XOR = 0x4d
    const val LOG_AND = 0x50
    const val LOG_OR = 0x51
    const val SHL = 0x58
    const val SHR = 0x59
    const val SHL2 = 0x5a
    const val SHR2 = 0x5b
    const val NEW = 0x60
    const val DELETE = 0x61
    const val UNKNOWN_68 = 0x68
    const val CALL2 = 0x70

    val jumpOps = setOf(JUMP_IF_NOT, JUMP_IF, JUMP, CALL_GLOBAL)
    val indexOps = setOf(PUSH, POP_TO, CALL, CALL2, NEW)
}
