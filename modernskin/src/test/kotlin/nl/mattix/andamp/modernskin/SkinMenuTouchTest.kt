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
import nl.mattix.andamp.modernskin.skin.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which object a touch reaches, and menus a script opens from its handlers through the pointer
 * calls a host makes: shown in reply to a touch and never from a timer, and the press they
 * interrupt gives nothing more.
 */
class SkinMenuTouchTest {
    // a Play button with a label over its middle, a seek bar, a slider hidden under a layer, a
    // trigger layer under a screen (as a vis trigger lies under the vis), a slider uncovered only
    // along its left edge, a seek bar under a twin at alpha 1, and a layer a script only animates
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="300">""" +
                        """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/>""" +
                        """<text id="label" x="16" y="16" w="8" h="8" default="P"/>""" +
                        """<slider id="seek" action="seek" x="10" y="60" w="150" h="10" orientation="h"/>""" +
                        """<slider id="hidden" action="eq_band" param="1" x="120" y="120" w="13" h="40" orientation="v"/>""" +
                        """<layer id="cover" x="110" y="115" w="60" h="50"/>""" +
                        """<layer id="trigger" x="10" y="120" w="60" h="30"/>""" +
                        """<layer id="screen" x="10" y="120" w="60" h="30"/>""" +
                        """<slider id="edge" action="eq_band" param="2" x="200" y="10" w="40" h="40" orientation="v"/>""" +
                        """<layer id="edge.top" x="200" y="10" w="40" h="12"/>""" +
                        """<layer id="edge.bottom" x="200" y="38" w="40" h="12"/>""" +
                        """<layer id="edge.middle" x="204" y="22" w="36" h="16"/>""" +
                        """<slider id="shown.seek" action="seek" x="200" y="200" w="80" h="10" orientation="h"/>""" +
                        """<slider id="twin.seek" action="seek" x="200" y="200" w="80" h="10" orientation="h" alpha="1"/>""" +
                        """<layer id="animated" x="200" y="250" w="40" h="30"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val shown = ArrayList<SkinMenu>()
    private val logs = ArrayList<String>()

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = null

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = text.length * 6

            override fun log(message: String) {
                logs += message
            }

            override fun invalidate() = Unit

            override fun chooseFromMenu(menu: SkinMenu): Int {
                shown += menu
                return 0
            }
        }

    private var now = 0L
    private val player = FakePlayer().apply { lengthMs = 200_000 }
    private val rt = SkinRuntime(files, host, player).apply { clock = { now } }

    /** What the script's handlers saw, in order: "enter" and "leave" from the area events. */
    private val heard = ArrayList<String>()

    private val recorder =
        object : MakiObject {
            override val makiGuid = RECORDER

            override fun makiCall(
                name: String,
                args: List<Value>,
            ): Value = Value.Null.also { heard += name }
        }

    private lateinit var button: Button
    private lateinit var seek: Slider
    private lateinit var menu: PopupMenu

    @Before
    fun load() {
        rt.load()
        button = checkNotNull(rt.mainLayout?.findObject("play")) as Button
        seek = checkNotNull(rt.mainLayout?.findObject("seek")) as Slider
        menu = PopupMenu(rt).apply { makiCall("addcommand", listOf(Value.Str("Item"), Value.Int(1))) }
    }

    /**
     * A script holding [target] and [menu]: each of [menuOn] (events with x and y) opens the menu,
     * and `onEnterArea` and `onLeaveArea` are recorded in [heard].
     */
    private fun script(
        target: MakiObject,
        vararg menuOn: String,
    ) {
        val methods = ArrayList<MethodRef>()

        fun method(
            cls: Int,
            name: String,
        ): Int = methods.size.also { methods += MethodRef(cls, name) }
        val popAt = method(MENU_CLASS, "popatmouse")
        val enter = method(RECORDER_CLASS, "enter")
        val leave = method(RECORDER_CLASS, "leave")
        val code =
            listOf(
                // 0: a handler with x and y opens the menu
                Opcodes.POP to 0,
                Opcodes.POP to 0,
                // 2: a handler without arguments opens the menu
                Opcodes.PUSH to MENU_VAR,
                Opcodes.CALL to popAt,
                Opcodes.POP to 0,
                Opcodes.PUSH to ZERO_VAR,
                Opcodes.RETURN to 0,
                // 7: onEnterArea
                Opcodes.PUSH to RECORDER_VAR,
                Opcodes.CALL to enter,
                Opcodes.POP to 0,
                Opcodes.PUSH to ZERO_VAR,
                Opcodes.RETURN to 0,
                // 12: onLeaveArea
                Opcodes.PUSH to RECORDER_VAR,
                Opcodes.CALL to leave,
                Opcodes.POP to 0,
                Opcodes.PUSH to ZERO_VAR,
                Opcodes.RETURN to 0,
            ).mapIndexed { i, (op, arg) -> Op(op, arg, if (op == Opcodes.CALL && arg != popAt) 0 else -1, i) }
        val bindings =
            menuOn.map { event -> Binding(TARGET_VAR, method(TARGET_CLASS, event), if (event in NO_ARGS) 2 else 0) } +
                Binding(TARGET_VAR, method(TARGET_CLASS, "onenterarea"), 7) +
                Binding(TARGET_VAR, method(TARGET_CLASS, "onleavearea"), 12)
        val program =
            MakiProgram(
                id = "menus",
                version = 0,
                classes = listOf(StdApi.guidOf("GuiObject")!!, StdApi.guidOf("PopupMenu")!!, RECORDER),
                methods = methods,
                variables =
                    listOf(
                        variable(Value.Obj(target)),
                        variable(Value.Obj(menu)),
                        variable(Value.Obj(recorder)),
                        VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0)),
                    ),
                bindings = bindings,
                code = code,
            )
        rt.scripts += Script(program, recorder, rt)
    }

    private fun variable(v: Value) = VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = v)

    private fun tap(o: GuiObject) {
        val x = o.rect.left + 2
        val y = o.rect.top + 2
        rt.pointerDown(x, y)
        rt.pointerUp(x, y)
    }

    @Test
    fun `a menu from a release handler shows, and the button gives no click after it`() {
        script(button, "onleftbuttonup")

        tap(button)

        assertEquals(1, shown.size)
        assertEquals(Player.Status.STOPPED, player.status)
        assertFalse(button.pressed)
        assertEquals(listOf("enter", "leave"), heard)
    }

    @Test
    fun `a menu from a timer is not shown`() {
        val timer = Timer(rt).apply { delay = 10 }
        script(timer, "ontimer")
        timer.start()

        now += 50
        rt.tick()

        assertTrue(shown.isEmpty())
        assertTrue(logs.toString(), logs.any { "outside a touch" in it })
    }

    @Test
    fun `a menu on press lets go of the button before the area events, so neither comes`() {
        script(button, "onleftbuttondown")

        tap(button)

        assertEquals(1, shown.size)
        assertEquals(Player.Status.STOPPED, player.status)
        assertTrue("no enter without a leave: $heard", heard.isEmpty())
    }

    @Test
    fun `after a menu ends the press a long press does nothing, and the next touch works as usual`() {
        script(button, "onrightbuttonup")
        val x = button.rect.left + 2
        val y = button.rect.top + 2
        rt.pointerDown(x, y)
        rt.gesture { menu.makiCall("popatmouse", emptyList()) }

        rt.longPress(x, y)
        assertEquals("the long press after the menu opened none", 1, shown.size)

        rt.pointerDown(x, y)
        rt.longPress(x, y)
        assertEquals("a new touch's long press opens the skin's menu", 2, shown.size)
        assertFalse("a long press lets go of the button", button.pressed)
    }

    @Test
    fun `a host's cancel ends the press quietly, with the area events paired`() {
        script(button)
        val x = button.rect.left + 2
        val y = button.rect.top + 2
        rt.pointerDown(x, y)

        rt.pointerCancel()
        rt.pointerUp(x, y)

        assertFalse(button.pressed)
        assertEquals(Player.Status.STOPPED, player.status)
        assertEquals(listOf("enter", "leave"), heard)
    }

    @Test
    fun `a host's cancel during a drag lets go of the slider`() {
        rt.pointerDown(seek.rect.left + 100, seek.rect.top + 5)

        rt.pointerCancel()

        assertFalse(seek.dragging)
    }

    @Test
    fun `a menu the slider's release handler pops while the host cancels the drag is not shown, and the next touch's is`() {
        script(seek, "onleftbuttonup")
        val x = seek.rect.left + 100
        val y = seek.rect.top + 5
        rt.pointerDown(x, y)

        rt.pointerCancel()
        assertFalse(seek.dragging)
        assertTrue("no menu over the host's long press: $shown", shown.isEmpty())
        assertTrue(logs.toString(), logs.any { "while a press ends without a release" in it })

        rt.pointerDown(x, y)
        rt.pointerUp(x, y)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a menu during a drag lets go of the slider without a final position`() {
        player.play()
        val y = seek.rect.top + 5
        rt.pointerDown(seek.rect.left + 100, y)
        assertTrue(seek.dragging)

        rt.gesture { menu.makiCall("popatmouse", emptyList()) }
        rt.pointerUp(seek.rect.left + 100, y)

        assertFalse(seek.dragging)
        assertTrue("no seek happened: ${player.positionMs}", player.positionMs < 1_000)
    }

    @Test
    fun `a slider hidden under a layer takes no touch, so the layer's menu opens`() {
        val cover = checkNotNull(rt.mainLayout?.findObject("cover"))
        val hidden = checkNotNull(rt.mainLayout?.findObject("hidden"))
        script(cover, "onrightbuttonup")
        rt.touchSlop = 24

        for (y in hidden.rect.top until hidden.rect.bottom) {
            for (x in hidden.rect.left until hidden.rect.right) assertEquals(cover, rt.touchTarget(x, y))
        }
        val x = hidden.rect.left + 5
        val y = hidden.rect.top + 5
        rt.pointerDown(x, y)
        rt.longPress(x, y)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a script's layer under another layer still takes the touch`() {
        val trigger = checkNotNull(rt.mainLayout?.findObject("trigger"))
        val screen = checkNotNull(rt.mainLayout?.findObject("screen"))
        script(trigger, "onrightbuttonup")
        rt.touchSlop = 24
        assertEquals(screen, rt.hitTest(40, 135))

        assertEquals(trigger, rt.touchTarget(40, 135))
        rt.pointerDown(40, 135)
        rt.longPress(40, 135)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a slider uncovered only along the edge facing the finger still takes it`() {
        val edge = checkNotNull(rt.mainLayout?.findObject("edge"))
        rt.touchSlop = 24

        assertEquals(edge, rt.touchTarget(195, 30))
    }

    @Test
    fun `a seek bar's twin at alpha 1 takes touches beside the bar`() {
        val twin = checkNotNull(rt.mainLayout?.findObject("twin.seek"))
        rt.touchSlop = 24

        assertEquals(twin, rt.touchTarget(240, 197))
        assertEquals(twin, rt.touchTarget(240, 213))
    }

    @Test
    fun `a layer a script only animates takes no touch beside it`() {
        val animated = checkNotNull(rt.mainLayout?.findObject("animated"))
        script(animated, "ontargetreached")
        rt.touchSlop = 24

        assertTrue(rt.touchTarget(195, 265) !== animated)
    }

    @Test
    fun `a label over the middle of a button still lets the button take the tap`() {
        val label = checkNotNull(rt.mainLayout?.findObject("label"))
        assertEquals(label, rt.hitTest(20, 20))

        assertEquals(button, rt.touchTarget(20, 20))
        rt.pointerDown(20, 20)
        rt.pointerUp(20, 20)
        assertEquals(Player.Status.PLAYING, player.status)
    }

    private companion object {
        /** Events whose handlers take no x and y. */
        val NO_ARGS = setOf("ontimer", "ontargetreached")
        const val RECORDER = "recorder"
        const val TARGET_CLASS = 0
        const val MENU_CLASS = 1
        const val RECORDER_CLASS = 2
        const val TARGET_VAR = 0
        const val MENU_VAR = 1
        const val RECORDER_VAR = 2
        const val ZERO_VAR = 3
    }
}
