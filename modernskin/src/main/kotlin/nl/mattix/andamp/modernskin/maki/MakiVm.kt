// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.maki

import java.util.BitSet
import java.util.IdentityHashMap

/** What the VM needs from the player: new objects and a log. */
interface MakiHost {
    /** `new Timer`, `new PopupMenu`, and so on. Null when the class cannot be made here. */
    fun create(guid: String): MakiObject?

    fun log(message: String)

    /**
     * Fires an event on every script listening to it; a script calling `x.onSomething()` does this
     * and gets the handler's return value (Wasabi SDK makiapi: the event's result goes back).
     */
    fun dispatchEvent(
        target: MakiObject,
        event: String,
        args: List<Value>,
    ): Value = Value.Null
}

/**
 * One loaded script: its program, its variable cells and the bindings that turn player events into
 * runs of the bytecode. Variables of type System are filled with [system] when the script is made.
 */
class Script(
    val program: MakiProgram,
    val system: MakiObject,
    private val host: MakiHost,
) {
    val cells: List<Cell> =
        program.variables.map { decl ->
            val initial =
                when {
                    decl.constant != null -> decl.constant!!
                    decl.guid != null && decl.classVar == null && StdApi.isA(decl.guid, "System") -> Value.Obj(system)
                    decl.guid != null && decl.classVar == null && decl.system -> Value.of(host.create(decl.guid))
                    else -> decl.initial
                }
            Cell(decl.kind, initial)
        }

    /** Member variables of each class variable (`Class Layer Mains; Global Mains a, b;`). */
    private val classMembers: Map<Int, List<Int>> =
        program.variables
            .withIndex()
            .filter { it.value.classVar != null }
            .groupBy({ it.value.classVar!! }, { it.index })

    private val bindingsByEvent: Map<String, List<Binding>> =
        program.bindings.groupBy { program.methods[it.method].name }

    /** Whether a handler here is bound to [target]: a variable holding it, or a class it belongs to. */
    fun listensTo(target: MakiObject): Boolean = program.bindings.any { b -> binds(b, target) }

    /** Whether a handler here is bound to [target] for one of [events]. */
    fun listensTo(
        target: MakiObject,
        events: Set<String>,
    ): Boolean = program.bindings.any { b -> program.methods[b.method].name in events && binds(b, target) }

    private fun binds(
        b: Binding,
        target: MakiObject,
    ): Boolean =
        cells[b.variable].value.asObject === target ||
            classMembers[b.variable]?.any { cells[it].value.asObject === target } == true

    /**
     * Whether a right-click handler here bound to [target] can reach a popup menu: one for
     * onRightButtonDown or onRightButtonUp, and also onRightClick when [isButton]. Read from the
     * bytecode (see [reachFrom]); no script runs.
     */
    fun rightClickMenu(
        target: MakiObject,
        isButton: Boolean,
    ): Boolean = reachesMenu(target, rightClickEvents(isButton))

    /** Whether a handler here for one of [events] bound to [target] can reach a popup menu, read from the bytecode. */
    fun reachesMenu(
        target: MakiObject,
        events: Set<String>,
    ): Boolean = !disabled && handlerStarts(target, events).any { reach(it).popsMenu }

    /**
     * The events the handlers here for [events] bound to [target] fire by calling them:
     * `x.onLeftClick()`, or a Button's `x.leftClick()`.
     */
    fun eventsCalled(
        target: MakiObject,
        events: Set<String>,
    ): Set<String> = if (disabled) emptySet() else handlerStarts(target, events).flatMapTo(HashSet()) { reach(it).calls }

    /**
     * Whether a handler here for one of [events] bound to [target] can read where the mouse is
     * (`getMousePosX`, `getMousePosY`), read from the bytecode: MMD3's knobs turn with the mouse
     * that way while the button is down, from a timer the press starts.
     */
    fun readsMouse(
        target: MakiObject,
        events: Set<String>,
    ): Boolean = !disabled && handlerStarts(target, events).any { reach(it).readsMouse }

    /** Where the handlers here for [events] bound to [target] start in the code. */
    private fun handlerStarts(
        target: MakiObject,
        events: Set<String>,
    ): List<Int> = events.flatMap { e -> bindingsByEvent[e].orEmpty().filter { binds(it, target) }.map { it.codeIndex } }

    /** What a handler's code can reach: a popup menu, the events it fires by calling them, and where the mouse is. */
    private class Reach(
        val popsMenu: Boolean,
        val calls: Set<String>,
        val readsMouse: Boolean,
    )

    /** The [Reach] from each handler start, worked out the first time it is asked for. */
    private val reaches = HashMap<Int, Reach>()

    private fun reach(start: Int): Reach = reaches.getOrPut(start) { reachFrom(start) }

    /**
     * Instructions [reachFrom] may still read in this script: in all, no more than the script may
     * run in one frame. Once they are used up, a handler not read yet offers no menu.
     */
    internal var walkBudget = STEPS_PER_FRAME

    /** [walkBudget] ran out, and the log said so. */
    private var walkCut = false

    /** The instructions the walk running now has reached; each walk clears what it marked. */
    private var reached: BitSet? = null

    /**
     * Walks every instruction a run from [start] could reach, without running any: the next one,
     * the targets of jumps, and a global call's function and the instruction it returns to, each
     * path up to its RETURN. A popup menu counts wherever it is reached, under any condition. A
     * walk that [walkBudget] cuts short keeps what it found.
     */
    private fun reachFrom(start: Int): Reach {
        val code = program.code
        val seen = reached ?: BitSet(code.size).also { reached = it }
        // every instruction reached, in the order it is read
        val order = ArrayList<Int>()
        var popsMenu = false
        var readsMouse = false
        val calls = HashSet<String>()

        fun visit(ip: Int) {
            if (ip in code.indices && !seen[ip]) {
                seen.set(ip)
                order += ip
            }
        }
        visit(start)
        var next = 0
        while (next < order.size && spendWalk()) {
            val ip = order[next++]
            val op = code[ip]
            when (op.opcode) {
                Opcodes.RETURN -> {
                    Unit
                }

                Opcodes.JUMP -> {
                    visit(op.arg)
                }

                Opcodes.JUMP_IF, Opcodes.JUMP_IF_NOT, Opcodes.CALL_GLOBAL -> {
                    visit(op.arg)
                    visit(ip + 1)
                }

                Opcodes.CALL, Opcodes.CALL2 -> {
                    val name = program.methods.getOrNull(op.arg)?.name ?: ""
                    if (name in MENU_CALLS) popsMenu = true
                    if (name in MOUSE_CALLS) readsMouse = true
                    when {
                        name.startsWith("on") -> calls += name

                        // a Button's leftClick() fires onLeftClick; its rightClick() fires
                        // onRightClick, which a right click on a Button counts already
                        name == "leftclick" -> calls += "onleftclick"
                    }
                    visit(ip + 1)
                }

                else -> {
                    visit(ip + 1)
                }
            }
        }
        order.forEach(seen::clear)
        return Reach(popsMenu, calls, readsMouse)
    }

    /** Takes one instruction from [walkBudget]; false when none is left, which the log says once. */
    private fun spendWalk(): Boolean {
        if (walkBudget > 0) {
            walkBudget--
            return true
        }
        if (!walkCut) {
            walkCut = true
            host.log("${program.id}: right-click menus read up to the limit, the rest not offered")
        }
        return false
    }

    /** The events this script listens to, for a quick check before dispatching: none once it is [disabled]. */
    val events: Set<String> get() = if (disabled) emptySet() else bindingsByEvent.keys

    /**
     * Steps the script may still run before the host's next [refill] (each frame): a script that
     * keeps a frame busy is stopped there, not only per handler.
     */
    internal var budget = STEPS_PER_FRAME

    fun refill() {
        budget = STEPS_PER_FRAME
    }

    /** Steps run since the last [refill]. */
    val stepsSinceRefill: Int get() = STEPS_PER_FRAME - budget

    /** A script that failed [MAX_FAILURES] times is switched off: its handlers no longer run. */
    var disabled = false
        private set
    private var failures = 0

    /**
     * Fires [event] on [target]: every binding whose variable holds the target runs. Returns the
     * number of handlers that ran.
     */
    fun dispatch(
        target: MakiObject,
        event: String,
        args: List<Value>,
    ): Int {
        val bindings = bindingsByEvent[event] ?: return 0
        var ran = 0
        for (b in bindings) {
            val cell = cells[b.variable]
            val held = cell.value.asObject
            val match =
                when {
                    held === target -> {
                        true
                    }

                    classMembers.containsKey(b.variable) &&
                        classMembers.getValue(b.variable).any { cells[it].value.asObject === target } -> {
                        // a handler on a class variable: it sees the member that fired as itself
                        cell.assign(Value.Obj(target))
                        true
                    }

                    else -> {
                        false
                    }
                }
            if (match) {
                lastReturn = run(b.codeIndex, args, event)
                ran++
            }
        }
        return ran
    }

    /** What the last handler this script ran returned. */
    var lastReturn: Value = Value.Null

    /** The member variables this script keeps on objects (`EQ_p12.setTo`), by object and lowercase name. */
    private val members = IdentityHashMap<MakiObject, HashMap<String, Cell>>()
    private var memberCount = 0

    /** The slot of member [name] on [owner], made the first time as a [kind] holding that kind's zero. */
    internal fun member(
        owner: MakiObject?,
        name: String,
        kind: Kind,
    ): Cell {
        // assigning null gives a cell its kind's zero: 0, 0.0, false, "" or null
        fun fresh() = Cell(kind, Value.Null).apply { assign(Value.Null) }
        if (owner == null) {
            host.log("${program.id}: member $name on null")
            return fresh()
        }
        val slots = members.getOrPut(owner) { HashMap() }
        return slots.getOrPut(name.lowercase()) {
            if (++memberCount > MAX_MEMBERS) throw MakiRuntimeException("more than $MAX_MEMBERS member variables")
            fresh()
        }
    }

    /** Drops the member variables kept on a deleted object. */
    internal fun forget(owner: MakiObject) {
        members.remove(owner)?.let { memberCount -= it.size }
    }

    @Suppress("TooGenericExceptionCaught") // whatever a handler throws is logged and ends that run, not the player
    fun run(
        start: Int,
        args: List<Value>,
        what: String,
    ): Value {
        if (disabled) return Value.Null
        return try {
            Interpreter(this, host).run(start, args)
        } catch (e: MakiRuntimeException) {
            fail(what, e.message)
        } catch (e: RuntimeException) {
            // a native call given what it cannot use (a skin's mistake or a hostile file)
            fail(what, e.toString())
        } catch (_: StackOverflowError) {
            fail(what, "too deep")
        }
    }

    private fun fail(
        what: String,
        why: String?,
    ): Value {
        host.log("${program.id}: $what stopped: $why")
        if (++failures >= MAX_FAILURES) {
            disabled = true
            host.log("${program.id}: switched off after $failures failures")
        }
        return Value.Null
    }

    companion object {
        private const val STEPS_PER_FRAME = 4_000_000
        private const val MAX_FAILURES = 20
        private const val MAX_MEMBERS = 65_536

        /** The PopupMenu calls that show a menu. */
        private val MENU_CALLS = setOf("popatmouse", "popatxy")

        /** The System calls that read where the mouse is. */
        private val MOUSE_CALLS = setOf("getmouseposx", "getmouseposy")

        private val RIGHT_BUTTON = setOf("onrightbuttondown", "onrightbuttonup")
        private val RIGHT_BUTTON_AND_CLICK = RIGHT_BUTTON + "onrightclick"

        /** The events a right click fires: the right button going down and up, and on a Button also onRightClick. */
        fun rightClickEvents(isButton: Boolean): Set<String> = if (isButton) RIGHT_BUTTON_AND_CLICK else RIGHT_BUTTON
    }
}

class MakiRuntimeException(
    message: String,
) : Exception(message)

private class Interpreter(
    private val script: Script,
    private val host: MakiHost,
) {
    private val program = script.program
    private val cells = script.cells
    private val stack = ArrayDeque<Cell>()
    private val callStack = ArrayDeque<Int>()

    /** The instruction running, for error messages. */
    private var current: Op? = null

    private fun pop(): Cell =
        stack.removeLastOrNull()
            ?: throw MakiRuntimeException(
                "stack underflow at ${current?.offset} (opcode 0x${current?.opcode?.toString(16)})",
            )

    private fun push(c: Cell) {
        if (stack.size >= MAX_STACK) throw MakiRuntimeException("stack overflow")
        stack.addLast(c)
    }

    private fun pushValue(
        v: Value,
        kind: Kind = kindOf(v),
    ) {
        push(Cell(kind, v))
    }

    private fun kindOf(v: Value) =
        when (v) {
            is Value.Int -> Kind.INT
            is Value.Num -> Kind.DOUBLE
            is Value.Bool -> Kind.BOOLEAN
            is Value.Str -> Kind.STRING
            else -> Kind.OBJECT
        }

    /** Runs a handler; returns what it left for its caller (the value pushed before its last RETURN). */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount") // one arm per opcode and a throw per limit a script hits
    fun run(
        start: Int,
        args: List<Value>,
    ): Value {
        // the handler's prologue pops its parameters, first parameter first
        for (a in args.asReversed()) pushValue(a)
        var ip = start
        var steps = 0
        val code = program.code
        while (ip in code.indices) {
            if (++steps > STEP_LIMIT) throw MakiRuntimeException("step limit at ${code[ip].offset}")
            if (--script.budget < 0) throw MakiRuntimeException("frame budget used up at ${code[ip].offset}")
            val op = code[ip]
            current = op
            when (op.opcode) {
                Opcodes.PUSH -> {
                    push(cells[op.arg])
                }

                Opcodes.POP -> {
                    pop()
                }

                Opcodes.POP_TO -> {
                    cells[op.arg].assign(pop().value)
                }

                Opcodes.EQ -> {
                    compare { a, b -> equal(a, b) }
                }

                Opcodes.NE -> {
                    compare { a, b -> !equal(a, b) }
                }

                Opcodes.GT -> {
                    compare { a, b -> ordinal(a, b) > 0 }
                }

                Opcodes.GE -> {
                    compare { a, b -> ordinal(a, b) >= 0 }
                }

                Opcodes.LT -> {
                    compare { a, b -> ordinal(a, b) < 0 }
                }

                Opcodes.LE -> {
                    compare { a, b -> ordinal(a, b) <= 0 }
                }

                Opcodes.JUMP_IF_NOT -> {
                    if (!pop().value.truthy) ip = op.arg - 1
                }

                Opcodes.JUMP_IF -> {
                    if (pop().value.truthy) ip = op.arg - 1
                }

                Opcodes.JUMP -> {
                    ip = op.arg - 1
                }

                Opcodes.CALL, Opcodes.CALL2 -> {
                    call(op)
                }

                Opcodes.CALL_GLOBAL -> {
                    if (callStack.size >= MAX_CALL_DEPTH) throw MakiRuntimeException("calls nested too deep at ${op.offset}")
                    callStack.addLast(ip)
                    ip = op.arg - 1
                }

                Opcodes.RETURN -> {
                    val back = callStack.removeLastOrNull() ?: return stack.lastOrNull()?.value ?: Value.Null
                    ip = back
                }

                Opcodes.COMPLETE -> {
                    Unit
                }

                Opcodes.MEMBER -> {
                    val name = pop().value.asString
                    val owner = pop().value.asObject
                    push(script.member(owner, name, Kind.ofType(op.arg) ?: Kind.OBJECT))
                }

                Opcodes.MOV -> {
                    val src = pop()
                    val dst = pop()
                    dst.assign(src.value)
                    push(src)
                }

                Opcodes.POST_INC -> {
                    step(1, post = true)
                }

                Opcodes.POST_DEC -> {
                    step(-1, post = true)
                }

                Opcodes.PRE_INC -> {
                    step(1, post = false)
                }

                Opcodes.PRE_DEC -> {
                    step(-1, post = false)
                }

                Opcodes.ADD -> {
                    arith(op) { a, b -> a + b }
                }

                Opcodes.SUB -> {
                    arith(op) { a, b -> a - b }
                }

                Opcodes.MUL -> {
                    arith(op) { a, b -> a * b }
                }

                Opcodes.DIV -> {
                    arith(op) { a, b -> if (b == 0.0) 0.0 else a / b }
                }

                Opcodes.MOD -> {
                    intOp { a, b -> if (b == 0) 0 else a % b }
                }

                Opcodes.AND -> {
                    intOp { a, b -> a and b }
                }

                Opcodes.OR -> {
                    intOp { a, b -> a or b }
                }

                Opcodes.XOR -> {
                    intOp { a, b -> a xor b }
                }

                Opcodes.SHL, Opcodes.SHL2 -> {
                    intOp { a, b -> a shl b }
                }

                Opcodes.SHR, Opcodes.SHR2 -> {
                    intOp { a, b -> a shr b }
                }

                Opcodes.NOT -> {
                    pushValue(Value.Int(if (pop().value.truthy) 0 else 1))
                }

                Opcodes.BIT_NOT -> {
                    pushValue(Value.Int(pop().value.asInt.inv()))
                }

                Opcodes.NEG -> {
                    val v = pop().value
                    pushValue(if (v is Value.Int) Value.Int(-v.v) else Value.Num(-v.asDouble))
                }

                Opcodes.LOG_AND -> {
                    val b = pop().value
                    val a = pop().value
                    pushValue(Value.Int(if (a.truthy && b.truthy) 1 else 0))
                }

                Opcodes.LOG_OR -> {
                    val b = pop().value
                    val a = pop().value
                    pushValue(Value.Int(if (a.truthy || b.truthy) 1 else 0))
                }

                Opcodes.NEW -> {
                    val guid = program.classes[op.arg]
                    val made = host.create(guid)
                    if (made == null) host.log("${program.id}: cannot make ${program.className(op.arg)}")
                    pushValue(Value.of(made), Kind.OBJECT)
                }

                Opcodes.DELETE -> {
                    // `delete x` is an expression to the compiler, which drops its value with the POP
                    // that always follows: take the object, leave a value in its place
                    pop().value.asObject?.let {
                        it.delete()
                        script.forget(it)
                    }
                    pushValue(Value.Null, Kind.OBJECT)
                }

                else -> {
                    throw MakiRuntimeException("opcode 0x${op.opcode.toString(16)} at ${op.offset}")
                }
            }
            ip++
        }
        return Value.Null
    }

    private fun call(op: Op) {
        val method = program.methods[op.arg]
        val guid = program.classes[method.classIndex]
        val argc =
            when {
                op.argc >= 0 -> {
                    op.argc
                }

                else -> {
                    StdApi.method(guid, method.name)?.params
                        ?: throw MakiRuntimeException("unknown method ${program.className(method.classIndex)}.${method.name}")
                }
            }
        // the compiler pushes the last argument first, so the first argument is on top
        val args = ArrayList<Value>(argc)
        repeat(argc) { args.add(pop().value) }
        val receiver = pop().value.asObject
        val ret = StdApi.method(guid, method.name)?.ret ?: 'A'
        if (receiver == null) {
            host.log("${program.id}: ${program.className(method.classIndex)}.${method.name} on null")
            pushValue(defaultFor(ret))
            return
        }
        var result = receiver.makiCall(method.name, args)
        // a stand-in object hands out stand-ins, so a script keeps going through objects this runtime lacks
        if (result === Value.Null && ret == 'O' && receiver is LenientObject) result = Value.Obj(receiver.another())
        if (result == null && method.name.startsWith("on")) {
            // calling an event fires it, so other handlers (and this script's own) run
            result = host.dispatchEvent(receiver, method.name, args)
        }
        if (result == null) {
            host.log("${program.id}: ${receiver.javaClass.simpleName}.${method.name}(${args.joinToString()}) not implemented")
            pushValue(defaultFor(ret))
            return
        }
        if (result is Value.Str &&
            result.v.length > MAX_STRING
        ) {
            throw MakiRuntimeException("${method.name} gave a string longer than $MAX_STRING")
        }
        pushValue(coerceReturn(result, ret))
    }

    private fun defaultFor(ret: Char): Value =
        when (ret) {
            'I', 'B' -> Value.Int(0)
            'F', 'D' -> Value.Num(0.0)
            'S' -> Value.Str("")
            else -> Value.Null
        }

    private fun coerceReturn(
        v: Value,
        ret: Char,
    ): Value =
        when (ret) {
            'I' -> if (v is Value.Int) v else Value.Int(v.asInt)
            'B' -> Value.Int(if (v.truthy) 1 else 0)
            'F', 'D' -> if (v is Value.Num) v else Value.Num(v.asDouble)
            'S' -> if (v is Value.Str) v else Value.Str(v.asString)
            else -> v
        }

    private inline fun compare(test: (Value, Value) -> Boolean) {
        val b = pop().value
        val a = pop().value
        pushValue(Value.Int(if (test(a, b)) 1 else 0))
    }

    private fun equal(
        a: Value,
        b: Value,
    ): Boolean =
        when {
            a is Value.Str && b is Value.Str -> a.v.equals(b.v, ignoreCase = true)
            a is Value.Obj || b is Value.Obj || a === Value.Null || b === Value.Null -> a.asObject === b.asObject
            a is Value.Str || b is Value.Str -> a.asString.equals(b.asString, ignoreCase = true)
            else -> a.asDouble == b.asDouble
        }

    private fun ordinal(
        a: Value,
        b: Value,
    ): Int = if (a is Value.Str && b is Value.Str) a.v.compareTo(b.v, ignoreCase = true) else a.asDouble.compareTo(b.asDouble)

    private fun step(
        delta: Int,
        post: Boolean,
    ) {
        val cell = pop()
        val old = cell.value
        val new = if (old is Value.Int) Value.Int(old.v + delta) else Value.Num(old.asDouble + delta)
        cell.assign(new)
        if (post) pushValue(old) else push(cell)
    }

    private inline fun arith(
        op: Op,
        f: (Double, Double) -> Double,
    ) {
        val b = pop().value
        val a = pop().value
        if (a is Value.Str || b is Value.Str) {
            if (op.opcode != Opcodes.ADD) throw MakiRuntimeException("string arithmetic at ${op.offset}")
            val joined = a.asString + b.asString
            if (joined.length > MAX_STRING) throw MakiRuntimeException("string longer than $MAX_STRING at ${op.offset}")
            pushValue(Value.Str(joined))
            return
        }
        val r = f(a.asDouble, b.asDouble)
        // Int op Int stays Int, except division, which the scripts use for ratios
        if (a !is Value.Num && b !is Value.Num && op.opcode != Opcodes.DIV) {
            pushValue(Value.Int(r.toInt()))
        } else {
            pushValue(Value.Num(r))
        }
    }

    private inline fun intOp(f: (Int, Int) -> Int) {
        val b = pop().value.asInt
        val a = pop().value.asInt
        pushValue(Value.Int(f(a, b)))
    }

    private companion object {
        const val STEP_LIMIT = 2_000_000
        const val MAX_STACK = 65_536
        const val MAX_CALL_DEPTH = 1_024
        const val MAX_STRING = 1 shl 20
    }
}
