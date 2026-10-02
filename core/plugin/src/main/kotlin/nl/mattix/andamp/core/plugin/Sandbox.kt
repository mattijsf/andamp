// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib

/**
 * The environment a plug-in's script runs in.
 *
 * It loads only the libraries a plug-in needs (base, string, table, math and bit32) and then
 * removes the names in [forbidden]. A script has no file system, clock or process, cannot
 * load more code and cannot read or set metatables.
 */
internal object Sandbox {
    /** Names that exist in a standard Lua and are nil here. */
    val forbidden =
        listOf(
            "io",
            "os",
            "require",
            "package",
            "debug",
            "load",
            "loadstring",
            "loadfile",
            "dofile",
            "rawget",
            "rawset",
            "rawequal",
            "rawlen",
            "setmetatable",
            "getmetatable",
            "collectgarbage",
            "coroutine",
            "newproxy",
        )

    /** The string library of the load running on this thread, which `("x"):upper()` resolves through. */
    private val strings = ThreadLocal<LuaValue>()

    init {
        // LuaJ keeps the metatable all strings share in one static field, which StringLib
        // fills only while it is empty. Left to StringLib, the first load's string table
        // would answer method calls in every later load, so a plug-in that replaced
        // string.sub would replace it for the others. Filled here first, it looks up the
        // library of the load on the calling thread.
        LuaString.s_metatable =
            LuaValue.tableOf(
                arrayOf(
                    LuaValue.INDEX,
                    object : TwoArgFunction() {
                        override fun call(
                            string: LuaValue,
                            key: LuaValue,
                        ): LuaValue = strings.get()?.get(key) ?: LuaValue.NIL
                    },
                ),
            )
    }

    /** A fresh environment, for a script that will run on the calling thread. */
    fun globals(watchdog: Watchdog = Watchdog()): Globals {
        val globals = Globals()
        // the base library brings print, pairs, type, tostring and the like
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(StringLib())
        strings.set(globals.get("string"))
        globals.load(TableLib())
        globals.load(JseMathLib())
        globals.load(Bit32Lib())
        // a Globals built by hand has no compiler until one is installed
        LuaC.install(globals)
        forbidden.forEach { globals.set(it, LuaValue.NIL) }
        // PackageLib is loaded only so that the other libraries can register
        globals.set("package", LuaValue.NIL)
        globals.set("_G", LuaValue.NIL)
        // installed as the interpreter's hook only: the script gets no `debug` table
        globals.debuglib = watchdog
        return globals
    }

    /**
     * Stops a script the host has given up on.
     *
     * The interpreter calls its debug hook before every instruction. It never checks the
     * thread's interrupt flag, so the hook is the one place a running script can be stopped
     * from outside. The hook throws an [Error] and not a LuaError, because `pcall` catches
     * LuaError and Exception.
     *
     * The rest of DebugLib, which keeps a call stack for tracebacks, is overridden to do
     * nothing, so a load that is not abandoned costs one field read per instruction.
     */
    class Watchdog : DebugLib() {
        @Volatile
        var abandoned = false

        override fun onInstruction(
            pc: Int,
            v: Varargs,
            top: Int,
        ) {
            if (abandoned) throw Abandoned()
        }

        override fun onCall(f: LuaFunction) = Unit

        override fun onCall(
            c: LuaClosure,
            varargs: Varargs,
            stack: Array<LuaValue>,
        ) = Unit

        override fun onReturn() = Unit

        // there is no call stack to trace, so an error's message stays as it was given
        override fun traceback(level: Int) = ""
    }

    /** Thrown on the abandoned load's own thread. */
    class Abandoned : Error("the host stopped waiting for this plug-in")
}
