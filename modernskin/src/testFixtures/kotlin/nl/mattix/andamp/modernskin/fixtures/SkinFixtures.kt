// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.fixtures

import nl.mattix.andamp.modernskin.maki.Binding
import nl.mattix.andamp.modernskin.maki.Kind
import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.MakiProgram
import nl.mattix.andamp.modernskin.maki.MethodRef
import nl.mattix.andamp.modernskin.maki.Op
import nl.mattix.andamp.modernskin.maki.Opcodes
import nl.mattix.andamp.modernskin.maki.Script
import nl.mattix.andamp.modernskin.maki.StdApi
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.maki.VarDecl
import nl.mattix.andamp.modernskin.skin.ConfigItemObj
import nl.mattix.andamp.modernskin.skin.Layout
import nl.mattix.andamp.modernskin.skin.PopupMenu
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Slider
import nl.mattix.andamp.modernskin.skin.Timer
import nl.mattix.andamp.modernskin.skin.WindowId

// What tests and the debug activity's check mode do to a loaded skin the way its scripts would.
// The host itself goes through SkinRuntime's API only; these stand in for a skin's scripts.

private fun SkinRuntime.layout(window: WindowId): Layout =
    checkNotNull(
        windows.firstOrNull {
            it.windowId == window
        },
    ) { "window ${window.raw} not on screen" }

private fun SkinRuntime.mainLayoutOrFail(): Layout = checkNotNull(mainLayout) { "no main window" }

/**
 * Makes a popup menu with [commands] (text to id) and shows it under the finger, as a script's
 * `PopupMenu.popAtMouse()` does, inside a fresh gesture; returns the id picked, 0 for none.
 */
fun SkinRuntime.popScriptMenu(vararg commands: Pair<String, Int>): Int {
    val menu = PopupMenu(this)
    for ((text, id) in commands) menu.makiCall("addcommand", listOf(Value.Str(text), Value.Int(id)))
    return gesture(fresh = true) { checkNotNull(menu.makiCall("popatmouse", emptyList())).asInt }
}

/** Starts a timer firing every [delayMs], as a script's `new Timer; setDelay; start` does. */
fun SkinRuntime.startScriptTimer(delayMs: Int) {
    Timer(this).apply {
        delay = delayMs
        start()
    }
}

/** When the next of the skin's timers fires, in [SkinRuntime.now]'s ms; null when none runs. */
val SkinRuntime.nextTimerAt: Long? get() = timers.minOfOrNull { it.nextAt }

/** Moves and sizes [window] as a script's `Layout.resize(x, y, w, h)` does. */
fun SkinRuntime.resizeAsScript(
    window: WindowId,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
) {
    layout(window).makiCall("resize", listOf(Value.Int(x), Value.Int(y), Value.Int(w), Value.Int(h)))
}

/** Where a script reads [window] to be (`getLeft`, `getTop`). */
fun SkinRuntime.placeAsScript(window: WindowId): Pair<Int, Int> {
    val l = layout(window)
    return checkNotNull(l.makiCall("getleft", emptyList())).asInt to checkNotNull(l.makiCall("gettop", emptyList())).asInt
}

/** Switches [container] to its layout [layout] (a shade and back), as a script's `switchToLayout` does. */
fun SkinRuntime.switchLayoutAsScript(
    container: String,
    layout: String,
) {
    checkNotNull(container(container)) { "no container $container" }.switchTo(layout)
}

/** Sets attribute [key] of object [id] in the main window to [value], as a script's `setXmlParam` does. */
fun SkinRuntime.setParamAsScript(
    id: String,
    key: String,
    value: String,
) {
    checkNotNull(mainLayoutOrFail().findObject(id)) { "no object $id" }.setXmlParam(key, value)
}

/**
 * Puts object [id] of the main window on its way to a target, as a script's `gotoTarget` does:
 * the windows count as sliding until [endSlidesAsScript].
 */
fun SkinRuntime.beginSlideAsScript(id: String) {
    moving.add(checkNotNull(mainLayoutOrFail().findObject(id)) { "no object $id" })
}

/** Ends every slide [beginSlideAsScript] began, as the slides reaching their targets do. */
fun SkinRuntime.endSlidesAsScript() {
    moving.clear()
}

/** The step slider [id] of the main window is at, as a script's `getPosition` reads it. */
fun SkinRuntime.sliderPosition(id: String): Int =
    (checkNotNull(mainLayoutOrFail().findObject(id)) { "no object $id" } as Slider).position

/** A Config item, as a script reaches it with getItem or newItem. */
class ScriptConfigItem internal constructor(
    private val item: ConfigItemObj,
) {
    /** Makes attribute [name] with [default], as `newAttribute` does; a stored value wins over the default. */
    fun option(
        name: String,
        default: String,
    ) {
        item.makiCall("newattribute", listOf(Value.Str(name), Value.Str(default)))
    }

    /** Reads attribute [name], as `getAttribute` does, without making it. */
    fun read(name: String) {
        item.makiCall("getattribute", listOf(Value.Str(name)))
    }
}

/** The Config item a skin lists its options for Winamp's Options menu in, made as getItem makes it. */
fun SkinRuntime.optionsItem(): ScriptConfigItem =
    ScriptConfigItem(checkNotNull(configItem(SkinOptions.OPTIONS_MENU, SkinOptions.OPTIONS_MENU)))

/** A Config item made as newItem([name], [guid]) makes it. */
fun SkinRuntime.newConfigItem(
    name: String,
    guid: String,
): ScriptConfigItem = ScriptConfigItem(checkNotNull(configItem(name, guid)))

/**
 * One event a recorder listens to: [event] on object [id] of the main window, or on System when
 * [id] is null, heard as [name] with [argc] arguments.
 */
class Listen(
    val id: String?,
    val event: String,
    val name: String,
    val argc: Int,
)

/**
 * Adds a script to the main window that hands each event of [listens] to [heard], with its
 * arguments as ints, as a skin's script handling those events would hear them.
 */
@Suppress("LongMethod") // one hand-assembled script: a handler per event, then the variables
fun SkinRuntime.recordEvents(
    listens: List<Listen>,
    heard: (name: String, args: List<Int>) -> Unit,
) {
    val main = mainLayoutOrFail()
    val system = main.systemObjects.single()
    val recorder =
        object : MakiObject {
            override val makiGuid = RECORDER

            override fun makiCall(
                name: String,
                args: List<Value>,
            ): Value = Value.Null.also { heard(name, args.map { it.asInt }) }
        }
    // variables: 0 System, 1 the recorder, 2 zero, 3 and 4 the arguments, then one per object listened to
    val objects = listens.mapNotNull { it.id }.distinct()
    val objectVar = objects.withIndex().associate { (i, id) -> id to OBJECT_VARS + i }
    val methods = ArrayList<MethodRef>()

    fun method(
        cls: Int,
        name: String,
    ): Int = methods.size.also { methods += MethodRef(cls, name) }
    val code = ArrayList<Op>()
    val bindings = ArrayList<Binding>()
    for (l in listens) {
        val variable = l.id?.let { objectVar.getValue(it) } ?: SYSTEM_VAR
        bindings += Binding(variable, method(SOURCE_CLASS, l.event), code.size)
        val call = method(RECORDER_CLASS, l.name)
        val temps = listOf(FIRST_VAR, SECOND_VAR).take(l.argc)
        for (t in temps) code += Op(Opcodes.POP_TO, t, -1, code.size)
        code += Op(Opcodes.PUSH, RECORDER_VAR, -1, code.size)
        for (t in temps.asReversed()) code += Op(Opcodes.PUSH, t, -1, code.size)
        code += Op(Opcodes.CALL, call, l.argc, code.size)
        code += Op(Opcodes.POP, 0, -1, code.size)
        code += Op(Opcodes.PUSH, ZERO_VAR, -1, code.size)
        code += Op(Opcodes.RETURN, 0, -1, code.size)
    }

    fun obj(o: MakiObject) = VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.Obj(o))

    fun int() = VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0))
    val program =
        MakiProgram(
            id = "recorder",
            version = 0,
            classes = listOf(checkNotNull(StdApi.guidOf("GuiObject")), RECORDER),
            methods = methods,
            variables =
                listOf(obj(system), obj(recorder), int(), int(), int()) +
                    objects.map { id -> obj(checkNotNull(main.findObject(id)) { "no object $id" }) },
            bindings = bindings,
            code = code,
        )
    scripts += Script(program, system, this)
}

private const val RECORDER = "0000000000000000000000000000beef"
private const val SOURCE_CLASS = 0
private const val RECORDER_CLASS = 1
private const val SYSTEM_VAR = 0
private const val RECORDER_VAR = 1
private const val ZERO_VAR = 2
private const val FIRST_VAR = 3
private const val SECOND_VAR = 4
private const val OBJECT_VARS = 5
