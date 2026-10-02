// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/** The JNI declarations for projectm-eval. See [Eel] for the rules. */
internal object EelNative {
    init {
        System.loadLibrary("andamp_avs")
    }

    external fun contextCreate(): Long

    external fun contextDestroy(context: Long)

    external fun contextResetVariables(context: Long)

    external fun registerVariable(
        context: Long,
        name: String,
    ): Long

    external fun variableGet(variable: Long): Double

    external fun variableSet(
        variable: Long,
        value: Double,
    )

    external fun codeCompile(
        context: Long,
        source: String,
    ): Long

    external fun codeDestroy(code: Long)

    external fun codeExecute(code: Long): Double

    external fun lastError(context: Long): String?
}

/**
 * A variable inside an [Eel] context: written before a run, read after it.
 *
 * Once its context closes the handle points into freed native memory, so the
 * value then reads as zero and writes are ignored.
 */
class EelVariable internal constructor(
    private val handle: Long,
    private val owner: Eel,
) {
    var value: Double
        get() = if (owner.isClosed) 0.0 else EelNative.variableGet(handle)
        set(value) {
            if (!owner.isClosed) EelNative.variableSet(handle, value)
        }
}

/** A compiled expression. It takes no arguments: its inputs and outputs are the context's variables. */
class EelCode internal constructor(
    private val handle: Long,
) {
    internal var destroyed = false
        private set

    fun run(): Double = if (destroyed) 0.0 else EelNative.codeExecute(handle)

    internal fun destroy() {
        if (destroyed) return
        destroyed = true
        EelNative.codeDestroy(handle)
    }
}

/**
 * One ns-eel scope: its variables, and the expressions compiled against them.
 *
 * ns-eel is the language AVS scripts in: a Super Scope's per-point code, a
 * Dynamic Movement's per-vertex code. The evaluator is projectm-eval, an
 * ns-eel2 reimplementation (MIT; see NOTICE.md), pinned as this module's own
 * submodule so the two visualizer modules stay independent.
 *
 * Variables are registered once and then written and read through the pointer
 * the evaluator hands back, so a per-point loop looks nothing up by name.
 *
 * Not thread-safe. A context belongs to one component on one render thread,
 * which is why the host mutex hooks in the JNI glue are empty.
 */
class Eel : AutoCloseable {
    private val context = EelNative.contextCreate()
    private val variables = mutableMapOf<String, EelVariable>()
    private val compiled = mutableListOf<EelCode>()
    private var closed = false

    internal val isClosed get() = closed

    init {
        check(context != 0L) { "could not create an ns-eel context" }
    }

    /** The variable called [name], made on first use. The same name is the same variable. */
    fun variable(name: String): EelVariable =
        variables.getOrPut(name) {
            val handle = EelNative.registerVariable(alive(), name)
            check(handle != 0L) { "could not register the variable \"$name\"" }
            EelVariable(handle, this)
        }

    /**
     * Compiles [source], or returns null if it does not compile; [lastError]
     * says why. The component that owns the code decides what to do, usually
     * running without that section.
     */
    fun compile(source: String): EelCode? {
        val handle = EelNative.codeCompile(alive(), source)
        if (handle == 0L) return null
        return EelCode(handle).also { compiled += it }
    }

    /** Why the last [compile] failed, with a line and column. Only meaningful right after one. */
    fun lastError(): String? = EelNative.lastError(alive())

    /** Sets every variable back to zero and keeps the variables themselves. */
    fun reset() = EelNative.contextResetVariables(alive())

    override fun close() {
        if (closed) return
        closed = true
        compiled.forEach { it.destroy() }
        compiled.clear()
        variables.clear()
        EelNative.contextDestroy(context)
    }

    private fun alive(): Long {
        check(!closed) { "this ns-eel context has been closed" }
        return context
    }
}
