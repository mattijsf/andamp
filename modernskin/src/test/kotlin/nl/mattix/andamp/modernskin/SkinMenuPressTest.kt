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
import nl.mattix.andamp.modernskin.skin.Button
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.PopupMenu
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Slider
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A press on an object with a skin menu, which a long press opens: the object hears the press
 * only once the touch is a tap or a drag, so a long press runs nothing the object does on a press.
 */
class SkinMenuPressTest {
    // a layer and a Play button with menus, a seek bar with a menu, a plain layer and a plain Play
    // button, an album art layer that moves the window, and a shade layout to switch to
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="200">""" +
                        """<layer id="layer" x="50" y="10" w="40" h="20"/>""" +
                        """<button id="button" action="PLAY" x="150" y="10" w="20" h="20"/>""" +
                        """<slider id="seek" action="seek" x="10" y="100" w="150" h="10" orientation="h"/>""" +
                        """<layer id="plain" x="50" y="150" w="40" h="20"/>""" +
                        """<button id="play" action="PLAY" x="150" y="150" w="20" h="20"/>""" +
                        """<layer id="art" x="220" y="60" w="40" h="40" move="1"/>""" +
                        """</layout><layout id="shade" w="300" h="20"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val shown = ArrayList<SkinMenu>()

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = text.length * 6

            override fun log(message: String) = Unit

            override fun invalidate() = Unit

            override fun chooseFromMenu(menu: SkinMenu): Int {
                shown += menu
                return 0
            }
        }

    private val player = FakePlayer().apply { lengthMs = 200_000 }
    private val rt = SkinRuntime(files, host, player).apply { touchSlop = 10 }

    /** What the handlers heard, in order: the event's short name, with x for the pointer events. */
    private val heard = ArrayList<String>()

    private val recorder =
        object : MakiObject {
            override val makiGuid = RECORDER

            override fun makiCall(
                name: String,
                args: List<Value>,
            ): Value = Value.Null.also { heard += (listOf(name) + args.map { it.asInt.toString() }).joinToString(" ") }
        }

    private lateinit var layer: GuiObject
    private lateinit var button: Button
    private lateinit var seek: Slider
    private lateinit var plain: GuiObject
    private lateinit var play: Button
    private lateinit var art: GuiObject
    private lateinit var menu: PopupMenu

    @Before
    fun load() {
        rt.load()
        val main = checkNotNull(rt.mainLayout)
        layer = checkNotNull(main.findObject("layer"))
        button = checkNotNull(main.findObject("button")) as Button
        seek = checkNotNull(main.findObject("seek")) as Slider
        plain = checkNotNull(main.findObject("plain"))
        play = checkNotNull(main.findObject("play")) as Button
        art = checkNotNull(main.findObject("art"))
        menu = PopupMenu(rt).apply { makiCall("addcommand", listOf(Value.Str("Item"), Value.Int(1))) }
    }

    /**
     * A script holding [target] that records the left button going down and up, moves and the area
     * events, and opens the menu on the right button going up when [withMenu].
     */
    private fun script(
        target: MakiObject,
        withMenu: Boolean = true,
    ) {
        val methods = ArrayList<MethodRef>()

        fun method(
            cls: Int,
            name: String,
        ): Int = methods.size.also { methods += MethodRef(cls, name) }
        val code = ArrayList<Pair<Int, Int>>()
        val bindings = ArrayList<Binding>()

        /** A handler for [event] that records [name], with the handler's x when [withX]. */
        fun record(
            event: String,
            name: String,
            withX: Boolean,
        ) {
            val call = method(RECORDER_CLASS, name)
            bindings += Binding(TARGET_VAR, method(TARGET_CLASS, event), code.size)
            if (withX) {
                code += Opcodes.POP_TO to X_VAR
                code += Opcodes.POP to 0
            }
            code += Opcodes.PUSH to RECORDER_VAR
            if (withX) code += Opcodes.PUSH to X_VAR
            code += Opcodes.CALL to call
            code += Opcodes.POP to 0
            code += Opcodes.PUSH to ZERO_VAR
            code += Opcodes.RETURN to 0
        }
        record("onleftbuttondown", "down", withX = true)
        record("onleftbuttonup", "up", withX = true)
        record("onmousemove", "move", withX = true)
        record("onenterarea", "enter", withX = false)
        record("onleavearea", "leave", withX = false)
        val popAt = method(MENU_CLASS, "popatmouse")
        if (withMenu) {
            bindings += Binding(TARGET_VAR, method(TARGET_CLASS, "onrightbuttonup"), code.size)
            code += Opcodes.POP to 0
            code += Opcodes.POP to 0
            code += Opcodes.PUSH to MENU_VAR
            code += Opcodes.CALL to popAt
            code += Opcodes.POP to 0
            code += Opcodes.PUSH to ZERO_VAR
            code += Opcodes.RETURN to 0
        }
        val withArg = methods.indices.filter { methods[it].name in setOf("down", "up", "move") }.toSet()
        val program =
            MakiProgram(
                id = "press",
                version = 0,
                classes = listOf(StdApi.guidOf("GuiObject")!!, StdApi.guidOf("PopupMenu")!!, RECORDER),
                methods = methods,
                variables =
                    listOf(
                        variable(Value.Obj(target)),
                        variable(Value.Obj(menu)),
                        variable(Value.Obj(recorder)),
                        VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0)),
                        VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0)),
                    ),
                bindings = bindings,
                code =
                    code.mapIndexed { i, (op, arg) ->
                        val argc =
                            when {
                                op != Opcodes.CALL || arg == popAt -> -1
                                arg in withArg -> 1
                                else -> 0
                            }
                        Op(op, arg, argc, i)
                    },
            )
        rt.scripts += Script(program, recorder, rt)
    }

    private fun variable(v: Value) = VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = v)

    @Test
    fun `a long press opens the menu and the object hears no press`() {
        script(layer)
        val x = layer.rect.left + 5
        val y = layer.rect.top + 5

        rt.pointerDown(x, y, seq = 1)
        assertEquals(1, rt.menuTarget)
        rt.pointerCancel()
        rt.rightClick(1)
        rt.pointerUp(x, y)

        assertEquals(1, shown.size)
        assertTrue("nothing of the press: $heard", heard.isEmpty())
    }

    @Test
    fun `a tap reaches the object on release, the press where the finger went down`() {
        script(layer)
        val x = layer.rect.left + 5
        val y = layer.rect.top + 5

        rt.pointerDown(x, y, seq = 1)
        assertTrue("held until the touch is a tap: $heard", heard.isEmpty())
        rt.pointerUp(x + 3, y)

        assertEquals(listOf("down $x", "enter", "up ${x + 3}", "leave"), heard)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a move within reach keeps the press back, and a move beyond it sends the press first`() {
        script(layer)
        val x = layer.rect.left + 5
        val y = layer.rect.top + 5

        rt.pointerDown(x, y, seq = 1)
        rt.pointerMove(x + 6, y + 6)
        assertTrue("still a tap or a long press: $heard", heard.isEmpty())
        rt.pointerMove(x + 20, y)
        rt.pointerMove(x + 25, y)
        rt.pointerUp(x + 25, y)

        assertEquals(listOf("down $x", "enter", "move ${x + 20}", "move ${x + 25}", "up ${x + 25}", "leave"), heard)
    }

    @Test
    fun `a right click drops the press even before the cancel`() {
        script(layer)
        val x = layer.rect.left + 5
        val y = layer.rect.top + 5

        rt.pointerDown(x, y, seq = 1)
        rt.rightClick(1)
        rt.pointerMove(x + 30, y)
        rt.pointerUp(x + 30, y)

        assertEquals(1, shown.size)
        assertTrue("nothing of the press: $heard", heard.isEmpty())
    }

    @Test
    fun `a button with a menu clicks on a tap, and a long press gives neither press nor click`() {
        script(button)
        val x = button.rect.left + 5
        val y = button.rect.top + 5

        rt.pointerDown(x, y, seq = 1)
        assertTrue("a held press shows the button pressed", button.pressed)
        rt.pointerCancel()
        assertFalse(button.pressed)
        rt.rightClick(1)
        assertTrue("nothing of the press: $heard", heard.isEmpty())
        assertEquals(Player.Status.STOPPED, player.status)

        rt.pointerDown(x, y, seq = 2)
        assertTrue(button.pressed)
        rt.pointerUp(x, y)
        assertEquals(listOf("down $x", "enter", "up $x", "leave"), heard)
        assertEquals(Player.Status.PLAYING, player.status)
        assertFalse(button.pressed)
    }

    @Test
    fun `a held button no longer shows pressed however its touch ends without a release`() {
        script(button)
        val x = button.rect.left + 5
        val y = button.rect.top + 5

        fun hold(seq: Int) {
            rt.pointerDown(x, y, seq)
            assertTrue("held, the button shows pressed", button.pressed)
        }

        hold(seq = 1)
        rt.rightClick(1)
        assertFalse("a right click without the host's cancel", button.pressed)

        hold(seq = 2)
        rt.pointerDown(plain.rect.left + 5, plain.rect.top + 5, seq = 3)
        assertFalse("a new touch", button.pressed)
        rt.pointerCancel()

        hold(seq = 4)
        rt.doubleTap(x, y, seq = 5)
        assertFalse("a double tap", button.pressed)

        hold(seq = 6)
        rt.longPress(x, y)
        assertFalse("a long press", button.pressed)

        hold(seq = 7)
        checkNotNull(rt.mainContainer).switchTo("shade")
        rt.pointerUp(x, y)
        assertFalse("its layout switched away before the release", button.pressed)

        assertTrue("nothing of any press: $heard", heard.isEmpty())
        assertEquals(Player.Status.STOPPED, player.status)
    }

    @Test
    fun `a slider with a menu, a plain layer and a plain button hear the press at once`() {
        script(seek)
        script(plain, withMenu = false)
        script(play, withMenu = false)

        rt.pointerDown(seek.rect.left + 100, seek.rect.top + 5, seq = 1)
        assertEquals("the seek bar has a menu", 1, rt.menuTarget)
        assertTrue(seek.dragging)
        assertEquals(listOf("down 110", "enter"), heard)
        rt.pointerCancel()

        heard.clear()
        rt.pointerDown(plain.rect.left + 5, plain.rect.top + 5, seq = 2)
        assertEquals(listOf("down ${plain.rect.left + 5}", "enter"), heard)
        rt.pointerCancel()

        heard.clear()
        rt.pointerDown(play.rect.left + 5, play.rect.top + 5, seq = 3)
        assertTrue(play.pressed)
        assertEquals(listOf("down ${play.rect.left + 5}", "enter"), heard)
    }

    @Test
    fun `a press whose object left the screen before the release goes nowhere`() {
        script(layer)
        val x = layer.rect.left + 5
        val y = layer.rect.top + 5

        rt.pointerDown(x, y, seq = 1)
        checkNotNull(rt.mainContainer).switchTo("shade")
        rt.pointerUp(x, y)

        assertTrue("nothing of the press: $heard", heard.isEmpty())
    }

    @Test
    fun `an object with a menu that moves the window is a move target while its press is held back`() {
        script(art)
        val x = art.rect.left + 5
        val y = art.rect.top + 5

        rt.pointerDown(x, y, seq = 1)

        assertEquals("the album art has a menu", 1, rt.menuTarget)
        assertEquals("and moves the window", 1, rt.moveTarget)
        assertTrue("held until the touch is a tap or a drag: $heard", heard.isEmpty())
    }

    @Test
    fun `Winamp Modern's visualizer keeps its mode on a long press and changes it on a tap`() {
        val skin = ZipSkinFiles(File(localResource("/WinampModern566.wal").toURI()))
        val wm = SkinRuntime(skin, TestHost(skin), FakePlayer())
        wm.load()
        val trigger = checkNotNull(wm.allObjects.firstOrNull { it.id == "main.vis.trigger" && it.visible })
        val x = trigger.rect.left + trigger.rect.width / 2
        val y = trigger.rect.top + trigger.rect.height / 2

        fun mode() =
            wm.privateInts.entries
                .single { it.key.endsWith("/visualizer mode") }
                .value
        val before = mode()

        wm.pointerDown(x, y, seq = 1)
        wm.pointerCancel()
        wm.rightClick(1)
        assertEquals("a long press leaves the mode", before, mode())

        wm.pointerDown(x, y, seq = 2)
        wm.pointerUp(x, y)
        assertNotEquals("a tap moves to the next mode", before, mode())
    }

    private companion object {
        const val RECORDER = "recorder"
        const val TARGET_CLASS = 0
        const val MENU_CLASS = 1
        const val RECORDER_CLASS = 2
        const val TARGET_VAR = 0
        const val MENU_VAR = 1
        const val RECORDER_VAR = 2
        const val ZERO_VAR = 3
        const val X_VAR = 4
    }
}
