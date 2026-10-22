// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.maki

/** A MAKI value. Integers and floats are kept apart because assignment to an Int variable truncates. */
sealed class Value {
    class Int(
        val v: kotlin.Int,
    ) : Value()

    class Num(
        val v: Double,
    ) : Value()

    class Bool(
        val v: Boolean,
    ) : Value()

    class Str(
        val v: String,
    ) : Value()

    class Obj(
        val o: MakiObject,
    ) : Value()

    object Null : Value()

    val truthy: Boolean
        get() =
            when (this) {
                is Int -> v != 0
                is Num -> v != 0.0
                is Bool -> v
                is Str -> v.isNotEmpty()
                is Obj -> true
                Null -> false
            }

    val asDouble: Double
        get() =
            when (this) {
                is Int -> v.toDouble()
                is Num -> v
                is Bool -> if (v) 1.0 else 0.0
                is Str -> v.trim().toDoubleOrNull() ?: 0.0
                else -> 0.0
            }

    val asInt: kotlin.Int
        get() =
            when (this) {
                is Int -> v
                is Num -> v.toInt()
                is Bool -> if (v) 1 else 0
                is Str -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt() ?: 0
                else -> 0
            }

    val asString: String
        get() =
            when (this) {
                is Str -> v
                is Int -> v.toString()
                is Num -> if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
                is Bool -> if (v) "1" else "0"
                is Obj -> o.toString()
                Null -> ""
            }

    val asObject: MakiObject?
        get() = (this as? Obj)?.o

    val isNumber: Boolean
        get() = this is Int || this is Num || this is Bool

    override fun toString(): String =
        when (this) {
            is Obj -> "Obj(${o.javaClass.simpleName})"
            Null -> "null"
            else -> asString
        }

    companion object {
        fun of(b: Boolean) = Bool(b)

        fun of(i: kotlin.Int) = Int(i)

        fun of(d: Double) = Num(d)

        fun of(s: String?) = if (s == null) Null else Str(s)

        fun of(o: MakiObject?) = if (o == null) Null else Obj(o)
    }
}

/** A variable slot. Assignment coerces to the declared kind, as the compiler assumes. */
class Cell(
    val kind: Kind,
    initial: Value,
) {
    var value: Value = initial
        private set

    fun assign(v: Value) {
        value =
            when (kind) {
                Kind.INT -> if (v is Value.Int) v else Value.Int(v.asInt)
                Kind.FLOAT, Kind.DOUBLE -> if (v is Value.Num) v else Value.Num(v.asDouble)
                Kind.BOOLEAN -> if (v is Value.Bool) v else Value.Bool(v.truthy)
                Kind.STRING -> if (v is Value.Str) v else Value.Str(v.asString)
                Kind.OBJECT -> if (v is Value.Obj || v === Value.Null) v else Value.Null
            }
    }

    override fun toString() = "Cell($kind=$value)"
}

/** An object that stands in for one the runtime lacks; its object-returning methods hand out more of them. */
interface LenientObject : MakiObject {
    fun another(): MakiObject
}

/** Anything a script can hold in an object variable and call methods on. */
interface MakiObject {
    /** The class GUID, lowercase hex without dashes, so the VM can check what the object is. */
    val makiGuid: String

    /** Runs a method named in `std.mi` (lowercase). Returns null when the method is unknown here. */
    fun makiCall(
        name: String,
        args: List<Value>,
    ): Value?

    /** A script's `delete`: the object stops what it does (a timer no longer fires). */
    fun delete() = Unit
}
