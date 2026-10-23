// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.maki.LenientObject
import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.MakiRuntimeException
import nl.mattix.andamp.modernskin.maki.StdApi
import nl.mattix.andamp.modernskin.maki.Value

/** `new Timer`: fires `onTimer` every `delay` milliseconds while started. */
class Timer(
    private val rt: SkinRuntime,
) : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("Timer") ?: ""
    var delay = 0
    var running = false
    var nextAt = 0L

    fun start() {
        if (delay <= 0) return
        running = true
        nextAt = rt.now() + delay
        // started again while running, it only starts its wait over
        if (this !in rt.timers) rt.timers.add(this)
    }

    fun stop() {
        running = false
        rt.timers.remove(this)
    }

    override fun delete() = stop()

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str("Timer")
            }

            "getid" -> {
                Value.Str("")
            }

            "setdelay" -> {
                delay = args[0].asInt
                if (running) nextAt = rt.now() + delay
                Value.Null
            }

            "getdelay" -> {
                Value.Int(delay)
            }

            "start" -> {
                Value.Null.also { start() }
            }

            "stop" -> {
                Value.Null.also { stop() }
            }

            "isrunning" -> {
                Value.Bool(running)
            }

            "getskipped" -> {
                Value.Int(0)
            }

            else -> {
                null
            }
        }
}

/**
 * `new PopupMenu`: commands, separators and submenus the script adds. Popping it up asks the host
 * through [SkinRuntime.menuChoice] and answers the picked command's id, or 0.
 */
class PopupMenu(
    private val rt: SkinRuntime,
) : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("PopupMenu") ?: ""

    private sealed interface Entry

    private class Command(
        val id: Int,
        val text: String,
        var checked: Boolean,
        var disabled: Boolean,
    ) : Entry

    private class Submenu(
        val text: String,
        val menu: PopupMenu?,
    ) : Entry

    private object Separator : Entry

    /** What the script added, in order. */
    private val entries = ArrayList<Entry>()

    /**
     * The menu as the host shows it, as it stands now. It holds at most [MAX_ITEMS] items in all and
     * [MAX_DEPTH] levels of submenus, so a menu that holds itself ends.
     */
    fun toMenu(): SkinMenu = snapshot(0, intArrayOf(MAX_ITEMS))

    /** This menu's items while [left] (the items still allowed in the whole tree) lasts. */
    private fun snapshot(
        depth: Int,
        left: IntArray,
    ): SkinMenu {
        val items = ArrayList<SkinMenu.Item>()
        for (e in entries) {
            if (left[0] <= 0) break
            val item =
                when (e) {
                    is Command -> {
                        SkinMenu.Command(e.id, shownText(e.text), e.checked, e.disabled)
                    }

                    is Submenu -> {
                        e.menu?.takeIf { depth < MAX_DEPTH }?.let { sub ->
                            left[0]--
                            SkinMenu.Submenu(shownText(e.text), sub.snapshot(depth + 1, left))
                        }
                    }

                    Separator -> {
                        SkinMenu.Separator
                    }
                }
            // a submenu counted itself before its items
            if (item != null && item !is SkinMenu.Submenu) left[0]--
            item?.let(items::add)
        }
        return SkinMenu(items)
    }

    private fun command(id: Int) = entries.filterIsInstance<Command>().firstOrNull { it.id == id }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str("PopupMenu")
            }

            "getid" -> {
                Value.Str("")
            }

            // addCommand(text, id, checked, disabled)
            "addcommand" -> {
                Value.Null.also {
                    entries +=
                        Command(
                            args[1].asInt,
                            args[0].asString,
                            args.getOrNull(2)?.truthy == true,
                            args.getOrNull(3)?.truthy == true,
                        )
                }
            }

            // addSubMenu(menu, text)
            "addsubmenu" -> {
                Value.Null.also { entries += Submenu(args[1].asString, args[0].asObject as? PopupMenu) }
            }

            "addseparator" -> {
                Value.Null.also { entries += Separator }
            }

            "checkcommand" -> {
                Value.Null.also { command(args[0].asInt)?.checked = args[1].truthy }
            }

            "disablecommand" -> {
                Value.Null.also { command(args[0].asInt)?.disabled = args[1].truthy }
            }

            "popatmouse", "popatxy" -> {
                Value.Int(rt.menuChoice(toMenu()))
            }

            "getnumcommands" -> {
                Value.Int(entries.size)
            }

            else -> {
                null
            }
        }

    private companion object {
        /** Submenu levels shown; deeper ones are left out. */
        const val MAX_DEPTH = 8

        /** Items shown in one menu and its submenus together; later ones are left out. */
        const val MAX_ITEMS = 512

        /** Characters shown of an item's words. */
        const val MAX_TEXT = 128

        /**
         * A menu item's words for the phone: without the keyboard shortcut Windows shows after a
         * tab, and without the `&` that marks the underlined letter (`&&` is a plain `&`).
         */
        fun shownText(text: String): String {
            val words = text.substringBefore('\t')
            return buildString {
                var i = 0
                while (i < words.length) {
                    if (words[i] == '&') i++
                    if (i < words.length) append(words[i])
                    i++
                }
            }.trim().take(MAX_TEXT)
        }
    }
}

/** `new Map`: reads values from a greyscale bitmap, as knob skins use to turn a touch into a level. */
class MakiMap(
    private val rt: SkinRuntime,
) : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("Map") ?: ""
    private var bitmap: String? = null

    /** ARGB at a point of the loaded bitmap element, or null outside it. */
    private fun pixel(
        x: Int,
        y: Int,
    ): Int? {
        val def = bitmap?.let { rt.bitmap(it) } ?: return null
        val (w, h) = rt.bitmapSize(def.id) ?: return null
        if (x !in 0 until w || y !in 0 until h) return null
        return rt.host.pixel(def.file, def.x + x, def.y + y)
    }

    @Suppress("CyclomaticComplexMethod") // one branch per method a script can call on a map
    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str("Map")
            }

            "getid" -> {
                Value.Str("")
            }

            "loadmap" -> {
                Value.Null.also { bitmap = args[0].asString }
            }

            // a map is a greyscale picture; its value at a point is that grey
            "getvalue" -> {
                Value.Int(pixel(args[0].asInt, args[1].asInt)?.let { (it shr 16) and 0xff } ?: 0)
            }

            "getargbvalue" -> {
                Value.Int(
                    pixel(args[0].asInt, args[1].asInt)?.let { p ->
                        when (args[2].asInt) {
                            0 -> p and 0xff
                            1 -> (p shr 8) and 0xff
                            2 -> (p shr 16) and 0xff
                            else -> (p ushr 24) and 0xff
                        }
                    } ?: 0,
                )
            }

            "inregion" -> {
                Value.Bool(pixel(args[0].asInt, args[1].asInt)?.let { (it ushr 24) > 0 } ?: false)
            }

            "getwidth" -> {
                Value.Int(bitmap?.let { rt.bitmapSize(it)?.first } ?: 0)
            }

            "getheight" -> {
                Value.Int(bitmap?.let { rt.bitmapSize(it)?.second } ?: 0)
            }

            "getregion" -> {
                Value.Null
            }

            else -> {
                null
            }
        }
}

/** `new List`: a plain list of values. */
class MakiList : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("List") ?: ""
    private val items = ArrayList<Value>()

    private companion object {
        /** A list stops growing here: a script that adds without end is stopped. */
        const val MAX_ITEMS = 100_000
    }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str("List")
            }

            "getid" -> {
                Value.Str("")
            }

            "additem" -> {
                Value.Null.also {
                    if (items.size <
                        MAX_ITEMS
                    ) {
                        items.add(args[0])
                    } else {
                        throw MakiRuntimeException("list longer than $MAX_ITEMS")
                    }
                }
            }

            "removeitem" -> {
                Value.Null.also { args[0].asInt.takeIf { it in items.indices }?.let { items.removeAt(it) } }
            }

            "enumitem" -> {
                items.getOrNull(args[0].asInt) ?: Value.Null
            }

            "finditem" -> {
                Value.Int(items.indexOfFirst { it.asString.equals(args[0].asString, true) })
            }

            "finditem2" -> {
                Value.Int(items.indexOfFirst { it.asObject === args[0].asObject })
            }

            "getnumitems" -> {
                Value.Int(items.size)
            }

            "removeall" -> {
                Value.Null.also { items.clear() }
            }

            else -> {
                null
            }
        }
}

/** Anything else a script makes: every call answers nothing, so the script keeps running. */
class Stub(
    override val makiGuid: String,
    private val name: String,
) : LenientObject {
    private val data = HashMap<String, String>()

    override fun another(): MakiObject = Stub("", "Object")

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> Value.Str(this.name)

            "getid" -> Value.Str("")

            "setdata" -> Value.Null.also { data["data"] = args[0].asString }

            // an unset config attribute reads as off
            "getdata" -> Value.Str(data["data"] ?: "0")

            else -> Value.Null
        }
}

/**
 * The skin's own settings (`Config`): items hold named attributes with string values. An
 * attribute is made with a default, and the same item and attribute come back for the same names,
 * so every script that changes one is heard by the others through `onDataChanged`.
 */
class ConfigObj(
    private val rt: SkinRuntime,
) : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("Config") ?: ""

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> Value.Str("Config")

            "getid" -> Value.Str("")

            // an item asked for before any script made it is made now; scripts load in any order
            "getitem", "getitembyguid" -> Value.of(rt.configItem(args[0].asString, args[0].asString))

            "newitem" -> Value.of(rt.configItem(args[0].asString, args[1].asString))

            else -> null
        }
}

class ConfigItemObj(
    private val rt: SkinRuntime,
    val name: String,
    val guid: String?,
) : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("ConfigItem") ?: ""
    val attributes = LinkedHashMap<String, ConfigAttributeObj>()

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str("ConfigItem")
            }

            "getid" -> {
                Value.Str("")
            }

            // Winamp's own items carry attributes no script made; an unknown one reads empty
            "getattribute" -> {
                Value.of(attributes.getOrPut(args[0].asString.lowercase()) { ConfigAttributeObj(rt, this, args[0].asString, "") })
            }

            "newattribute" -> {
                Value.of(
                    attributes.getOrPut(
                        args[0].asString.lowercase(),
                    ) { ConfigAttributeObj(rt, this, args[0].asString, args[1].asString) },
                )
            }

            "getguid" -> {
                Value.Str(guid ?: "")
            }

            "getname" -> {
                Value.Str(this.name)
            }

            else -> {
                null
            }
        }
}

/** A skin setting; a value in [SkinRuntime.storedSettings] wins over the script's default, as Winamp's studio.xnf does. */
class ConfigAttributeObj(
    private val rt: SkinRuntime,
    private val item: ConfigItemObj,
    val name: String,
    default: String,
) : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("ConfigAttribute") ?: ""
    var data: String = rt.storedSettings[name] ?: default
        private set

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str("ConfigAttribute")
            }

            "getid" -> {
                Value.Str("")
            }

            "getdata" -> {
                Value.Str(data)
            }

            // every setData tells the listeners, the same value included (Wasabi's Attribute::setDataNoCB)
            "setdata" -> {
                Value.Null.also {
                    data = args[0].asString
                    if (rt.storedSettings.put(this.name, data) != data) rt.settingsChanged()
                    if (dispatching < MAX_NESTED) {
                        dispatching++
                        try {
                            rt.dispatch(this, "ondatachanged", emptyList())
                        } finally {
                            dispatching--
                        }
                    }
                }
            }

            "getparentitem" -> {
                Value.Obj(item)
            }

            "getattributename" -> {
                Value.Str(this.name)
            }

            else -> {
                null
            }
        }

    private var dispatching = 0

    private companion object {
        /** A handler that sets its own attribute again stops echoing here. */
        const val MAX_NESTED = 4
    }
}

/** Winamp's own settings, grouped by GUID; unknown values read as zero, false or empty. */
class WinampConfigObj(
    private val rt: SkinRuntime,
) : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("WinampConfig") ?: ""
    private val groups = HashMap<String, WinampConfigGroupObj>()

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> Value.Str("WinampConfig")
            "getid" -> Value.Str("")
            "getgroup" -> Value.Obj(groups.getOrPut(args[0].asString.lowercase()) { WinampConfigGroupObj() })
            else -> null
        }
}

class WinampConfigGroupObj : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("WinampConfigGroup") ?: ""
    private val values = HashMap<String, String>()

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str("WinampConfigGroup")
            }

            "getid" -> {
                Value.Str("")
            }

            "getbool" -> {
                Value.Bool(values[args[0].asString.lowercase()]?.let { it != "0" && it.isNotEmpty() } ?: false)
            }

            "getint" -> {
                Value.Int(values[args[0].asString.lowercase()]?.toIntOrNull() ?: 0)
            }

            "getstring" -> {
                Value.Str(values[args[0].asString.lowercase()] ?: "")
            }

            "setbool", "setint", "setstring" -> {
                Value.Null.also {
                    values[args[0].asString.lowercase()] =
                        args.getOrNull(1)?.asString ?: ""
                }
            }

            else -> {
                null
            }
        }
}

/**
 * `Application`: who runs the skin. It answers as Winamp 5.666 does, the version these skins are
 * made for (Winamp3x and Winamp2000SP4 put "Winamp " + getVersionNumberString() in their title
 * bars, and turn themselves off for "5.8").
 */
class ApplicationObj : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("Application") ?: ""

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> Value.Str("Application")
            "getapplicationname" -> Value.Str("Winamp")
            "getversionstring" -> Value.Str("Winamp 5.666")
            "getversionnumberstring" -> Value.Str("5.666")
            "getbuildnumber" -> Value.Int(BUILD)
            "getguid" -> Value.Str("")
            "getcommandline", "getapplicationpath", "getsettingspath", "getworkingpath" -> Value.Str("")
            "isshuttingdown" -> Value.Bool(false)
            "shutdown", "cancelshutdown", "setworkingpath" -> Value.Null
            else -> null
        }

    private companion object {
        /** Winamp 5.666's build number. */
        const val BUILD = 3516
    }
}
