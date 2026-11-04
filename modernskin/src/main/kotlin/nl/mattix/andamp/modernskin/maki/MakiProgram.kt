// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.maki

/** A compiled MAKI script, read from a `.maki` file. See MakiParser for the layout. */
internal class MakiProgram(
    internal val id: String,
    internal val version: Int,
    /** Class GUIDs, lowercase hex without dashes, in the order the file declares them. */
    internal val classes: List<String>,
    internal val methods: List<MethodRef>,
    internal val variables: List<VarDecl>,
    internal val bindings: List<Binding>,
    internal val code: List<Op>,
) {
    internal fun className(index: Int): String = StdApi.byGuid[classes[index]]?.name ?: classes[index]
}

internal class MethodRef(
    internal val classIndex: Int,
    internal val name: String,
)

internal enum class Kind {
    INT,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    STRING,
    OBJECT,
    ;

    internal companion object {
        /** The primitive kind a type number stands for, as the variables table numbers types; null for any other. */
        internal fun ofType(type: Int): Kind? =
            when (type) {
                2 -> INT
                3 -> FLOAT
                4 -> DOUBLE
                5 -> BOOLEAN
                6 -> STRING
                else -> null
            }
    }
}

internal class VarDecl(
    internal val kind: Kind,
    /** For objects: the class GUID. */
    internal val guid: String?,
    /** For a variable declared with `Class X Y`: the index of the class variable it belongs to. */
    internal val classVar: Int?,
    internal val global: Boolean,
    internal val initial: Value,
    /** A predeclared global the runtime fills in: System, Config, PlDir and the like. */
    internal val system: Boolean = false,
) {
    /** A string literal bound to this variable by the constants section. */
    internal var constant: Value? = null
}

internal class Binding(
    internal val variable: Int,
    internal val method: Int,
    internal val codeIndex: Int,
)

internal class Op(
    internal val opcode: Int,
    /**
     * Variable, method or class index, a member's type, or a code index for jumps (a byte offset
     * while MakiParser reads the code).
     */
    internal val arg: Int,
    /** The argument count carried by a stack-protected call, or -1. */
    internal val argc: Int,
    /** Byte offset in the code section, for messages. */
    internal val offset: Int,
)

internal object Opcodes {
    internal const val PUSH = 0x01
    internal const val POP = 0x02
    internal const val POP_TO = 0x03
    internal const val EQ = 0x08
    internal const val NE = 0x09
    internal const val GT = 0x0a
    internal const val GE = 0x0b
    internal const val LT = 0x0c
    internal const val LE = 0x0d
    internal const val JUMP_IF_NOT = 0x10
    internal const val JUMP_IF = 0x11
    internal const val JUMP = 0x12
    internal const val CALL = 0x18
    internal const val CALL_GLOBAL = 0x19
    internal const val RETURN = 0x21
    internal const val COMPLETE = 0x28
    internal const val MOV = 0x30
    internal const val POST_INC = 0x38
    internal const val POST_DEC = 0x39
    internal const val PRE_INC = 0x3a
    internal const val PRE_DEC = 0x3b
    internal const val ADD = 0x40
    internal const val SUB = 0x41
    internal const val MUL = 0x42
    internal const val DIV = 0x43
    internal const val MOD = 0x44
    internal const val AND = 0x48
    internal const val OR = 0x49
    internal const val NOT = 0x4a
    internal const val BIT_NOT = 0x4b
    internal const val NEG = 0x4c
    internal const val XOR = 0x4d
    internal const val LOG_AND = 0x50
    internal const val LOG_OR = 0x51
    internal const val SHL = 0x58
    internal const val SHR = 0x59
    internal const val SHL2 = 0x5a
    internal const val SHR2 = 0x5b
    internal const val NEW = 0x60
    internal const val DELETE = 0x61

    /**
     * `object.name` for a variable a script keeps on each object of a class it declares: pops the
     * name and the object, leaves the variable's slot. The immediate is the variable's type.
     */
    internal const val MEMBER = 0x68
    internal const val CALL2 = 0x70

    internal val jumpOps = setOf(JUMP_IF_NOT, JUMP_IF, JUMP, CALL_GLOBAL)
    internal val indexOps = setOf(PUSH, POP_TO, CALL, CALL2, NEW, MEMBER)
}
