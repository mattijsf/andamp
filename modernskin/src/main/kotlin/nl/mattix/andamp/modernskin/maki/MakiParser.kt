// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.maki

import java.nio.ByteBuffer
import java.nio.ByteOrder

class MakiFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Reads a `.maki` file: a fixed sequence of sections without headers, all little endian.
 *
 * The layout follows the community description of the bytecode (Webamp's maki-bytecode.md, MIT):
 * header, class GUIDs, method imports, variables, string constants, event bindings, code, then
 * optional debug data this reader ignores.
 */
object MakiParser {
    private const val MAGIC = "FG"
    private const val MAX_TABLE = 1 shl 16

    @Suppress("TooGenericExceptionCaught") // any read can fail on a damaged file; each failure becomes a MakiFormatException
    fun parse(
        bytes: ByteArray,
        id: String,
    ): MakiProgram =
        try {
            read(bytes, id)
        } catch (e: MakiFormatException) {
            throw e
        } catch (e: RuntimeException) {
            // a cut-off or damaged file reads past its end or out of its tables
            throw MakiFormatException("$id: damaged (${e.javaClass.simpleName})", e)
        }

    private fun read(
        bytes: ByteArray,
        id: String,
    ): MakiProgram {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = String(ByteArray(2).also { buf.get(it) }, Charsets.ISO_8859_1)
        if (magic != MAGIC) throw MakiFormatException("$id: not a MAKI file (magic '$magic')")
        buf.short // 1027 in every file seen
        val version = buf.int
        val classes = readClasses(buf, id)
        val methods = readMethods(buf, id, classes)
        val variables = readVariables(buf, id, version, classes)
        readConstants(buf, id, variables)
        val rawBindings = readBindings(buf, id, variables)
        val (ops, offsetToIndex) = readCode(buf, id)
        val bindings =
            rawBindings.map { (v, m, off) ->
                Binding(v, m, offsetToIndex[off] ?: throw MakiFormatException("$id: binding at bad offset $off"))
            }
        return MakiProgram(id, version, classes, methods, variables, bindings, ops)
    }

    private fun count(
        buf: ByteBuffer,
        id: String,
        what: String,
    ): Int {
        val n = buf.int
        if (n < 0 || n > MAX_TABLE) throw MakiFormatException("$id: $what count $n out of range")
        return n
    }

    private fun readString(
        buf: ByteBuffer,
        id: String,
    ): String {
        val len = buf.short.toInt() and 0xffff
        if (len > buf.remaining()) throw MakiFormatException("$id: string runs past the end")
        val b = ByteArray(len).also { buf.get(it) }
        return String(b, Charsets.ISO_8859_1)
    }

    private fun readClasses(
        buf: ByteBuffer,
        id: String,
    ): List<String> {
        val n = count(buf, id, "class")
        return List(n) {
            val d1 = buf.int.toLong() and 0xffffffffL
            val d2 = buf.short.toInt() and 0xffff
            val d3 = buf.short.toInt() and 0xffff
            val tail = ByteArray(8).also { buf.get(it) }
            buildString {
                append("%08x%04x%04x".format(d1, d2, d3))
                tail.forEach { append("%02x".format(it.toInt() and 0xff)) }
            }
        }
    }

    private fun readMethods(
        buf: ByteBuffer,
        id: String,
        classes: List<String>,
    ): List<MethodRef> {
        val n = count(buf, id, "method")
        return List(n) {
            val classIndex = (buf.short.toInt() and 0xffff) and 0xff
            buf.short
            val name = readString(buf, id).lowercase()
            if (classIndex >= classes.size) throw MakiFormatException("$id: method $name on class $classIndex")
            MethodRef(classIndex, name)
        }
    }

    private fun readVariables(
        buf: ByteBuffer,
        id: String,
        version: Int,
        classes: List<String>,
    ): List<VarDecl> {
        val n = count(buf, id, "variable")
        val out = ArrayList<VarDecl>(n)
        repeat(n) {
            val typeOffset = buf.get().toInt() and 0xff
            val isObject = buf.get().toInt() != 0
            val subclass = buf.short.toInt() and 0xffff
            val u1 = buf.short.toInt() and 0xffff
            val u2 = buf.short.toInt() and 0xffff
            buf.short
            buf.short
            val global = buf.get().toInt() != 0
            val system = version > 22 && buf.get().toInt() != 0
            val decl =
                when {
                    subclass != 0 -> {
                        val base = out.getOrNull(typeOffset) ?: throw MakiFormatException("$id: subclass of bad var $typeOffset")
                        VarDecl(Kind.OBJECT, base.guid, typeOffset, global, Value.Null)
                    }

                    isObject -> {
                        val guid =
                            classes.getOrNull(typeOffset) ?: throw MakiFormatException("$id: object var of bad class $typeOffset")
                        VarDecl(Kind.OBJECT, guid, null, global, Value.Null, system)
                    }

                    else -> {
                        primitiveVar(id, typeOffset, u1, u2, global)
                    }
                }
            out.add(decl)
        }
        return out
    }

    private fun readConstants(
        buf: ByteBuffer,
        id: String,
        variables: List<VarDecl>,
    ) {
        val n = count(buf, id, "constant")
        repeat(n) {
            val index = buf.int
            val value = readString(buf, id)
            val decl = variables.getOrNull(index) ?: throw MakiFormatException("$id: constant for bad var $index")
            decl.constant = Value.Str(value)
        }
    }

    private fun readBindings(
        buf: ByteBuffer,
        id: String,
        variables: List<VarDecl>,
    ): List<Triple<Int, Int, Int>> {
        val n = count(buf, id, "binding")
        return List(n) {
            val v = buf.int
            val m = buf.int
            val off = buf.int
            if (v !in variables.indices) throw MakiFormatException("$id: binding on bad var $v")
            Triple(v, m, off)
        }
    }

    private fun readCode(
        buf: ByteBuffer,
        id: String,
    ): Pair<List<Op>, Map<Int, Int>> {
        val length = buf.int
        if (length < 0 || length > buf.remaining()) throw MakiFormatException("$id: code length $length")
        val start = buf.position()
        val end = start + length
        val raw = ArrayList<Op>()
        val offsetToIndex = HashMap<Int, Int>()
        while (buf.position() < end) {
            val op = readOp(buf, id, start, end)
            offsetToIndex[op.offset] = raw.size
            raw.add(op)
        }
        val ops =
            raw.map { op ->
                val resolved =
                    if (op.opcode in Opcodes.jumpOps) {
                        offsetToIndex[op.arg] ?: throw MakiFormatException("$id: jump to bad offset ${op.arg} from ${op.offset}")
                    } else {
                        op.arg
                    }
                Op(op.opcode, resolved, op.argc, op.offset)
            }
        return ops to offsetToIndex
    }

    /**
     * Reads the instruction at the buffer's position in the code section that runs from [start] to
     * [end]. A jump's argument is the byte offset it goes to, which [readCode] turns into a code index;
     * a member access's is the member's type.
     */
    private fun readOp(
        buf: ByteBuffer,
        id: String,
        start: Int,
        end: Int,
    ): Op {
        val pos = buf.position() - start
        val opcode = buf.get().toInt() and 0xff
        var arg = 0
        var argc = -1
        val hasArg = opcode in Opcodes.jumpOps || opcode in Opcodes.indexOps
        if (hasArg) {
            if (buf.position() + 4 > end) throw MakiFormatException("$id: opcode $opcode truncated at $pos")
            arg =
                if (opcode in Opcodes.jumpOps) {
                    buf.int + 5 + pos
                } else {
                    buf.int
                }
            if (buf.position() + 4 <= end) {
                val peek = buf.getInt(buf.position()).toLong() and 0xffffffffL
                if (peek in 0xffff0000L..0xffff000fL) {
                    buf.int
                    argc = (peek and 0xffff).toInt()
                }
            }
            if (opcode == Opcodes.CALL2) {
                val count = buf.get().toInt() and 0xff
                if (argc < 0) argc = count
            }
        }
        return Op(opcode, arg, argc, pos)
    }
}

/** A variable of a primitive type; [u1] and [u2] are the low and high halves of its initial value. */
private fun primitiveVar(
    id: String,
    typeOffset: Int,
    u1: Int,
    u2: Int,
    global: Boolean,
): VarDecl {
    val kind = Kind.ofType(typeOffset) ?: throw MakiFormatException("$id: primitive type $typeOffset")
    val initial =
        when (kind) {
            Kind.INT -> {
                Value.Int((u2 shl 16) or u1)
            }

            Kind.BOOLEAN -> {
                Value.Bool(u1 != 0)
            }

            Kind.FLOAT, Kind.DOUBLE -> {
                Value.Num(
                    java.lang.Float
                        .intBitsToFloat((u2 shl 16) or u1)
                        .toDouble(),
                )
            }

            Kind.STRING -> {
                Value.Str("")
            }

            Kind.OBJECT -> {
                Value.Null
            }
        }
    return VarDecl(kind, null, null, global, initial)
}
