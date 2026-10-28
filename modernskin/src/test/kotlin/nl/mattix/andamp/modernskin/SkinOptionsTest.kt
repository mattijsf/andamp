// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

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
import nl.mattix.andamp.modernskin.skin.ConfigAttributeObj
import nl.mattix.andamp.modernskin.skin.ConfigItemObj
import nl.mattix.andamp.modernskin.skin.ConfigObj
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.modernskin.skin.SkinOptions.Companion.OPTIONS_MENU
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The options a skin's scripts list for Winamp's Options menu (Config item CUSTOM_OPTIONSMENU_ITEMS)
 * and a pick of one: read as Winamp lists them in its Options menu, and flipped as a click there flips it.
 */
class SkinOptionsTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt =
        SkinRuntime(files, TestHost(files), FakePlayer().apply { clock = { 0L } }).apply {
            clock = { 0L }
            load()
        }

    private val config = ConfigObj(rt)

    private fun newItem(
        name: String,
        guid: String,
    ) = config.makiCall("newitem", listOf(Value.Str(name), Value.Str(guid)))!!.asObject as ConfigItemObj

    private fun getItem(key: String) = config.makiCall("getitem", listOf(Value.Str(key)))!!.asObject as ConfigItemObj

    private fun ConfigItemObj.add(
        name: String,
        value: String,
    ) = makiCall("newattribute", listOf(Value.Str(name), Value.Str(value)))!!.asObject as ConfigAttributeObj

    private fun options() = rt.skinOptions().entries

    private fun SkinOptions.Entry.text() =
        when (this) {
            is SkinOptions.Toggle -> text
            is SkinOptions.Submenu -> "$text >"
            SkinOptions.Separator -> "-"
        }

    private fun List<SkinOptions.Entry>.texts() = map { it.text() }

    private fun List<SkinOptions.Entry>.toggle(text: String) = filterIsInstance<SkinOptions.Toggle>().single { it.text == text }

    private fun List<SkinOptions.Entry>.sub(text: String) =
        filterIsInstance<SkinOptions.Submenu>().single { it.text == text }.entries

    @Test
    fun `attributes list in the order made, a known item's GUID a submenu, a dash a separator, others toggles ticked unless 0`() {
        val root = getItem(OPTIONS_MENU)
        val drawers = newItem("Drawers", "{11111111-0000-0000-0000-000000000001}")
        drawers.add("Animate &Config Drawer", "0")
        root.add("On", "1")
        root.add("Off", "0")
        root.add("-", "-")
        root.add("Empty", "")
        root.add("Big", "1000")
        root.add("Words", "abc")
        root.add("Equalizer\tAlt+G", "1")
        root.add("My &Drawers", "11111111-0000-0000-0000-000000000001")

        val entries = options()
        assertEquals(listOf("On", "Off", "-", "Empty", "Big", "Words", "Equalizer", "My Drawers >"), entries.texts())
        assertEquals(
            listOf(true, false, false, true, false, true),
            entries.filterIsInstance<SkinOptions.Toggle>().map { it.checked },
        )
        val animate = entries.sub("My Drawers").toggle("Animate Config Drawer")
        assertFalse(animate.checked)
        assertEquals(SkinOptions.Key(drawers.guid!!, "animate &config drawer"), animate.key)
    }

    @Test
    fun `a skin that never asked for the options item lists nothing, and building the list makes no item`() {
        newItem("Other", "{22222222-0000-0000-0000-000000000002}").add("Shown", "1")

        assertTrue(options().isEmpty())
        assertNull(rt.configItem(OPTIONS_MENU, null))
    }

    @Test
    fun `an attribute only read with getAttribute is not listed`() {
        val root = getItem(OPTIONS_MENU)
        root.makiCall("getattribute", listOf(Value.Str("Read")))
        root.add("Made", "1")

        assertEquals(listOf("Made"), options().texts())
    }

    @Test
    fun `submenus back to an ancestor, deeper than eight or empty are left out, as are stray separators`() {
        val root = getItem(OPTIONS_MENU)
        val loop = newItem("Loop", "{33333333-0000-0000-0000-000000000001}")
        loop.add("Back", OPTIONS_MENU)
        loop.add("Self", loop.guid!!)
        loop.add("Kept", "1")
        val empty = newItem("Empty", "{33333333-0000-0000-0000-000000000002}")
        empty.add("-", "-")
        // ten levels, each holding the next and a toggle
        val levels = (1..10).map { newItem("L$it", "{44444444-0000-0000-0000-0000000000${"%02d".format(it)}}") }
        levels.zipWithNext().forEach { (a, b) -> a.add("Next", b.guid!!) }
        levels.forEach { it.add("Leaf", "0") }
        root.add("-", "-")
        root.add("Loop", loop.guid!!)
        root.add("sep1", "-")
        root.add("Empty", empty.guid!!)
        root.add("sep2", "-")
        root.add("Deep", levels[0].guid!!)
        root.add("sep3", "-")

        val entries = options()
        assertEquals(listOf("Loop >", "-", "Deep >"), entries.texts())
        assertEquals(listOf("Kept"), entries.sub("Loop").texts())
        var depth = 1
        var level = entries.sub("Deep")
        while (level.any { it is SkinOptions.Submenu }) {
            level = level.sub("Next")
            depth++
        }
        assertEquals(listOf("Leaf"), level.texts())
        assertEquals(8, depth)
    }

    @Test
    fun `a toggle writes 1 over 0 or empty and 0 over anything else, kept in storedSettings as one settings change`() {
        val root = getItem(OPTIONS_MENU)
        for ((name, value) in listOf("Zero" to "0", "Empty" to "", "Big" to "1000", "Words" to "abc")) root.add(name, value)
        val expected = mapOf("Zero" to "1", "Empty" to "1", "Big" to "0", "Words" to "1")

        for ((name, after) in expected) {
            val settings = rt.settingsVersion
            val config = rt.configVersion
            assertTrue(rt.toggleSkinOption(options().toggle(name).key))
            assertEquals(after, root.attributes[name.lowercase()]!!.data)
            assertEquals(after, rt.storedSettings[name])
            assertEquals(settings + 1, rt.settingsVersion)
            assertNotEquals(config, rt.configVersion)
            assertEquals(after != "0", options().toggle(name).checked)
        }
    }

    @Test
    fun `toggling targets the exact item, the same attribute name in another item is untouched`() {
        val root = getItem(OPTIONS_MENU)
        root.add("Shared", "0")
        val other = newItem("Other", "{55555555-0000-0000-0000-000000000001}")
        val twin = other.add("Shared", "0")

        rt.toggleSkinOption(options().toggle("Shared").key)

        assertEquals("1", root.attributes["shared"]!!.data)
        assertEquals("0", twin.data)
    }

    @Test
    fun `toggling a separator, a submenu or an unknown key changes nothing and says so`() {
        val root = getItem(OPTIONS_MENU)
        val sub = newItem("Sub", "{66666666-0000-0000-0000-000000000001}")
        sub.add("In", "1")
        root.add("A", "1")
        root.add("Line", "-")
        root.add("Sub", sub.guid!!)
        val settings = rt.settingsVersion
        val guid = root.guid!!

        assertFalse(rt.toggleSkinOption(SkinOptions.Key(guid, "line")))
        assertFalse(rt.toggleSkinOption(SkinOptions.Key(guid, "sub")))
        assertFalse(rt.toggleSkinOption(SkinOptions.Key(guid, "nothing")))
        assertFalse(rt.toggleSkinOption(SkinOptions.Key("{00000000-0000-0000-0000-000000000000}", "a")))
        assertEquals("-", root.attributes["line"]!!.data)
        assertEquals(sub.guid, root.attributes["sub"]!!.data)
        assertEquals(settings, rt.settingsVersion)
    }

    @Test
    fun `getItem by GUID before newItem with that GUID gives one item, so its attributes show as a submenu`() {
        val guid = "{77777777-0000-0000-0000-000000000001}"
        val early = getItem(guid)
        getItem(OPTIONS_MENU).add("Later", guid)
        val made = newItem("Later", guid)
        made.add("Inside", "1")

        assertTrue(early === made)
        assertTrue(getItem("Later") === made)
        assertEquals(listOf("Inside"), options().sub("Later").texts())
    }

    @Test
    fun `a GUID that names no item is left out and cannot be flipped`() {
        val root = getItem(OPTIONS_MENU)
        root.add("Kept", "1")
        val lost = root.add("Lost", "{99999999-0000-0000-0000-000000000001}")
        root.add("Bare", "99999999-0000-0000-0000-000000000002")

        assertEquals(listOf("Kept"), options().texts())
        assertFalse(rt.toggleSkinOption(SkinOptions.Key(root.guid!!, "lost")))
        assertEquals("{99999999-0000-0000-0000-000000000001}", lost.data)
        assertNull(rt.storedSettings["Lost"])
    }

    @Test
    fun `an attribute read before it is made takes the default and the place newAttribute gives it`() {
        val root = getItem(OPTIONS_MENU)
        root.makiCall("getattribute", listOf(Value.Str("Late")))
        root.add("First", "0")
        val late = root.add("Late", "1")

        assertEquals("1", late.data)
        assertEquals(listOf("First", "Late"), options().texts())
        assertTrue(options().toggle("Late").checked)
    }

    @Test
    fun `an item asked for by name before newItem gives it a GUID is the one the GUID names`() {
        val early = getItem("Named")
        val guid = "{88888888-0000-0000-0000-000000000001}"
        val made = newItem("Named", guid)
        made.add("Inside", "1")
        getItem(OPTIONS_MENU).add("Named", guid)

        assertTrue(early === made)
        assertEquals(guid, made.guid)
        assertTrue(getItem(guid) === made)
        assertEquals(listOf("Inside"), options().sub("Named").texts())
    }

    @Test
    fun `a button bound to an option reads its value as the menu does`() {
        val root = getItem(OPTIONS_MENU)
        root.add("Spaced", " 1")
        root.add("Two", "2")

        assertTrue(rt.configFlag("Spaced"))
        assertEquals(1, rt.configValue("Spaced"))
        assertFalse("a button on value 1 is off at 2", rt.configFlag("Two"))
        assertEquals(2, rt.configValue("Two"))
        assertEquals(listOf(true, true), options().filterIsInstance<SkinOptions.Toggle>().map { it.checked })
    }

    @Test
    fun `the version moves when an item or attribute is made or a value changes, and stays on an unchanged setData`() {
        var last = rt.configVersion

        fun moved(): Boolean = (rt.configVersion != last).also { last = rt.configVersion }

        val root = getItem(OPTIONS_MENU)
        assertTrue(moved())
        root.makiCall("getattribute", listOf(Value.Str("A")))
        assertTrue(moved())
        val a = root.add("A", "1")
        assertTrue(moved())
        root.add("A", "1")
        assertFalse(moved())
        a.makiCall("setdata", listOf(Value.Str("0")))
        assertTrue(moved())
        a.makiCall("setdata", listOf(Value.Str("0")))
        assertFalse(moved())
        getItem(OPTIONS_MENU)
        assertFalse(moved())
    }

    @Test
    fun `a toggle is heard as onDataChanged`() {
        val a = getItem(OPTIONS_MENU).add("Heard", "0")
        val heard = ArrayList<String>()
        val recorder =
            object : MakiObject {
                override val makiGuid = RECORDER

                override fun makiCall(
                    name: String,
                    args: List<Value>,
                ): Value = Value.Null.also { heard += name }
            }

        fun obj(v: MakiObject) = VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.Obj(v))
        val code = listOf(Opcodes.PUSH to 1, Opcodes.CALL to 1, Opcodes.POP to 0, Opcodes.PUSH to 2, Opcodes.RETURN to 0)
        val program =
            MakiProgram(
                id = "options",
                version = 0,
                classes = listOf(StdApi.guidOf("ConfigAttribute")!!, RECORDER),
                methods = listOf(MethodRef(0, "ondatachanged"), MethodRef(1, "heard")),
                variables =
                    listOf(
                        obj(a),
                        obj(recorder),
                        VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0)),
                    ),
                bindings = listOf(Binding(0, 0, 0)),
                code = code.mapIndexed { i, (op, arg) -> Op(op, arg, if (op == Opcodes.CALL) 0 else -1, i) },
            )
        rt.scripts += Script(program, recorder, rt)

        rt.toggleSkinOption(options().toggle("Heard").key)

        assertEquals(listOf("heard"), heard)
        assertEquals("1", a.data)
    }

    @Test
    fun `Winamp Modern lists its options as Winamp does, and its scripts keep a drawer pair to one choice`() {
        val wal = ZipSkinFiles(File(localResource("/WinampModern566.wal").toURI()))
        val rt = SkinRuntime(wal, TestHost(wal), FakePlayer().apply { clock = { 0L } }).apply { clock = { 0L } }
        rt.load()
        repeat(5) { rt.tick() }

        val top = rt.skinOptions().entries
        assertEquals(listOf("Winamp Modern >"), top.texts())
        val modern = top.sub("Winamp Modern")
        val subs = modern.filterIsInstance<SkinOptions.Submenu>().map { it.text }
        assertEquals(
            listOf("Drawers", "Menus", "Main Windowshade Mode", "Vis Shortcut Button", "Notifications", "Songticker"),
            subs,
        )
        assertFalse(modern.sub("Drawers").toggle("Animate Config Drawer").checked)

        fun drawers() =
            rt
                .skinOptions()
                .entries
                .sub("Winamp Modern")
                .sub("Drawers")
        val top1 = drawers().toggle("Open Video/Vis from the top")
        val wasTicked = top1.checked
        if (wasTicked) rt.toggleSkinOption(drawers().toggle("Open Video/Vis from the bottom").key)
        assertFalse(drawers().toggle("Open Video/Vis from the top").checked)

        assertTrue(rt.toggleSkinOption(top1.key))
        assertTrue(drawers().toggle("Open Video/Vis from the top").checked)
        assertFalse(drawers().toggle("Open Video/Vis from the bottom").checked)

        rt.toggleSkinOption(top1.key)
        assertTrue(drawers().toggle("Open Video/Vis from the top").checked)
    }

    @Test
    fun `Big Bento lists eight entries at the top`() {
        val wal = ZipSkinFiles(File(localResource("/BigBento.wal").toURI()))
        val rt = SkinRuntime(wal, TestHost(wal), FakePlayer().apply { clock = { 0L } }).apply { clock = { 0L } }
        rt.load()
        repeat(5) { rt.tick() }

        assertEquals(8, rt.skinOptions().entries.size)
    }

    private companion object {
        const val RECORDER = "recorder"
    }
}
