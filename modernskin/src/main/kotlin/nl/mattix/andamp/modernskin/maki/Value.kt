// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.maki

/** A MAKI value. Integers and floats are kept apart because assignment to an Int variable truncates. */
internal sealed class Value {
    internal class Int(
        internal val v: kotlin.Int,
    ) : Value()

    internal class Num(
        internal val v: Double,
    ) : Value()

    internal class Bool(
        internal val v: Boolean,
    ) : Value()

    internal class Str(
        internal val v: String,
    ) : Value()

    internal class Obj(
        internal val o: MakiObject,
    ) : Value()

    internal object Null : Value()

    internal val truthy: Boolean
        get() =
            when (this) {
                is Int -> v != 0
                is Num -> v != 0.0
                is Bool -> v
                is Str -> v.isNotEmpty()
                is Obj -> true
                Null -> false
            }

    internal val asDouble: Double
        get() =
            when (this) {
                is Int -> v.toDouble()
                is Num -> v
                is Bool -> if (v) 1.0 else 0.0
                is Str -> v.trim().toDoubleOrNull() ?: 0.0
                else -> 0.0
            }

    internal val asInt: kotlin.Int
        get() =
            when (this) {
                is Int -> v
                is Num -> v.toInt()
                is Bool -> if (v) 1 else 0
                is Str -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt() ?: 0
                else -> 0
            }

    internal val asString: String
        get() =
            when (this) {
                is Str -> v
                is Int -> v.toString()
                is Num -> if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
                is Bool -> if (v) "1" else "0"
                is Obj -> o.toString()
                Null -> ""
            }

    internal val asObject: MakiObject?
        get() = (this as? Obj)?.o

    internal val isNumber: Boolean
        get() = this is Int || this is Num || this is Bool

    override fun toString(): String =
        when (this) {
            is Obj -> "Obj(${o.javaClass.simpleName})"
            Null -> "null"
            else -> asString
        }

    internal companion object {
        internal fun of(b: Boolean) = Bool(b)

        internal fun of(i: kotlin.Int) = Int(i)

        internal fun of(d: Double) = Num(d)

        internal fun of(s: String?) = if (s == null) Null else Str(s)

        internal fun of(o: MakiObject?) = if (o == null) Null else Obj(o)
    }
}

/** A variable slot. Assignment coerces to the declared kind, as the compiler assumes. */
internal class Cell(
    internal val kind: Kind,
    initial: Value,
) {
    internal var value: Value = initial
        private set

    internal fun assign(v: Value) {
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
internal interface LenientObject : MakiObject {
    public fun another(): MakiObject
}

/** Anything a script can hold in an object variable and call methods on. */
internal interface MakiObject {
    /** The class GUID, lowercase hex without dashes, so the VM can check what the object is. */
    public val makiGuid: String

    /** Runs a method named in `std.mi` (lowercase). Returns null when the method is unknown here. */
    public fun makiCall(
        name: String,
        args: List<Value>,
    ): Value?

    /** A script's `delete`: the object stops what it does (a timer no longer fires). */
    public fun delete() = Unit
}
