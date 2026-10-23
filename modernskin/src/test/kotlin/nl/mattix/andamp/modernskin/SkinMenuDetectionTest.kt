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
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.PopupMenu
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which objects have a menu of the skin's on a right click, read from the scripts' bytecode
 * without running them: handlers for the right button (and onRightClick on a Button) that can
 * reach PopupMenu.popAtMouse or popAtXY, one event further, and through a xui group.
 */
class SkinMenuDetectionTest {
    // a button, two layers, and a xui group that exposes its button child
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<groupdef id="tab.group" xuitag="test:tab" embed_xui="trap">""" +
                        """<button id="trap" x="0" y="0" w="20" h="20"/></groupdef>""" +
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="100">""" +
                        """<button id="button" x="10" y="10" w="20" h="20"/>""" +
                        """<layer id="layer" x="50" y="10" w="20" h="20"/>""" +
                        """<layer id="other" x="90" y="10" w="20" h="20"/>""" +
                        """<test:tab id="tab" x="130" y="10" w="20" h="20"/>""" +
                        """</layout></container>""",
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

    private val rt = SkinRuntime(files, host, FakePlayer())

    /** What the scripts ran: a handler that runs calls the recorder first. */
    private val heard = ArrayList<String>()

    private val recorder =
        object : MakiObject {
            override val makiGuid = RECORDER

            override fun makiCall(
                name: String,
                args: List<Value>,
            ): Value = Value.Null.also { heard += name }
        }

    private lateinit var button: GuiObject
    private lateinit var layer: GuiObject
    private lateinit var other: GuiObject
    private lateinit var tab: GuiObject
    private lateinit var trap: GuiObject
    private lateinit var menu: PopupMenu
    private lateinit var timer: Timer

    @Before
    fun load() {
        rt.load()
        val main = checkNotNull(rt.mainLayout)
        button = checkNotNull(main.findObject("button"))
        layer = checkNotNull(main.findObject("layer"))
        other = checkNotNull(main.findObject("other"))
        tab = checkNotNull(main.findObject("tab"))
        trap = checkNotNull(main.findObject("trap"))
        menu = PopupMenu(rt)
        timer = Timer(rt)
    }

    /** A handler: the variable holding its object (or its class), the event, and where its code starts. */
    private class Handler(
        val variable: Int,
        val event: String,
        val start: Int,
    )

    /**
     * A script whose variables hold the menu, a 0, the recorder, the timer, a class variable with
     * one member, and the objects [a] and [b]; [member] is the class variable's member.
     */
    private fun attach(
        handlers: List<Handler>,
        code: List<Pair<Int, Int>>,
        a: MakiObject? = null,
        b: MakiObject? = null,
        member: MakiObject? = null,
    ): Script {
        fun obj(v: MakiObject?) = VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.of(v))
        val variables =
            listOf(
                obj(menu),
                VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0)),
                obj(recorder),
                obj(timer),
                obj(null),
                VarDecl(Kind.OBJECT, guid = null, classVar = CLASS, global = true, initial = Value.of(member)),
                obj(a),
                obj(b),
            )
        val program =
            MakiProgram(
                id = "detect",
                version = 0,
                classes = listOf(StdApi.guidOf("GuiObject")!!, StdApi.guidOf("PopupMenu")!!, StdApi.guidOf("Timer")!!, RECORDER),
                methods = METHODS,
                variables = variables,
                bindings = handlers.map { h -> Binding(h.variable, METHODS.indexOfFirst { it.name == h.event }, h.start) },
                code = code.mapIndexed { i, (op, arg) -> Op(op, arg, if (op == Opcodes.CALL && arg == HEARD) 0 else -1, i) },
            )
        return Script(program, recorder, rt).also { rt.scripts += it }
    }

    @Test
    fun `a right button handler that pops a menu at the mouse is a skin menu`() {
        val s = attach(listOf(Handler(A, "onrightbuttonup", 0)), POPS_AT_MOUSE, a = layer)

        assertTrue(s.rightClickMenu(layer, isButton = false))
        assertTrue(rt.hasSkinMenu(layer))
        assertFalse("another object has none", rt.hasSkinMenu(other))
    }

    @Test
    fun `a menu behind a condition or a jump still counts`() {
        val conditional =
            listOf(
                Opcodes.PUSH to ZERO,
                Opcodes.JUMP_IF_NOT to 4,
                Opcodes.PUSH to ZERO,
                Opcodes.RETURN to 0,
                Opcodes.PUSH to MENU,
                Opcodes.CALL to POPATXY,
                Opcodes.POP to 0,
                Opcodes.PUSH to ZERO,
                Opcodes.RETURN to 0,
            )
        attach(listOf(Handler(A, "onrightbuttondown", 0)), conditional, a = layer)
        val jumped =
            listOf(
                Opcodes.JUMP to 3,
                Opcodes.PUSH to ZERO,
                Opcodes.RETURN to 0,
                Opcodes.PUSH to MENU,
                Opcodes.CALL to POPATMOUSE,
                Opcodes.POP to 0,
                Opcodes.PUSH to ZERO,
                Opcodes.RETURN to 0,
            )
        attach(listOf(Handler(A, "onrightbuttonup", 0)), jumped, a = other)

        assertTrue(rt.hasSkinMenu(layer))
        assertTrue(rt.hasSkinMenu(other))
    }

    @Test
    fun `a menu in a function the handler calls counts`() {
        val code =
            listOf(
                Opcodes.CALL_GLOBAL to 3,
                Opcodes.PUSH to ZERO,
                Opcodes.RETURN to 0,
            ) + POPS_AT_MOUSE
        attach(listOf(Handler(A, "onrightbuttonup", 0)), code, a = layer)

        assertTrue(rt.hasSkinMenu(layer))
    }

    @Test
    fun `onRightClick counts on a Button and not on a layer`() {
        val s = attach(listOf(Handler(A, "onrightclick", 0), Handler(B, "onrightclick", 0)), POPS_AT_MOUSE, a = button, b = layer)

        assertTrue(s.rightClickMenu(button, isButton = true))
        assertTrue(rt.hasSkinMenu(button))
        assertFalse("a layer hears no onRightClick", rt.hasSkinMenu(layer))
    }

    @Test
    fun `a handler on a class variable counts for its member`() {
        attach(listOf(Handler(CLASS, "onrightbuttonup", 0)), POPS_AT_MOUSE, member = layer)

        assertTrue(rt.hasSkinMenu(layer))
        assertFalse(rt.hasSkinMenu(other))
    }

    @Test
    fun `onRightClick calling onLeftClick reaches the menu of another script's onLeftClick`() {
        // as Bento's buttons do: the right click sends the button a left click, whose handler pops
        val route =
            listOf(
                Opcodes.PUSH to A,
                Opcodes.CALL to ONLEFTCLICK,
                Opcodes.POP to 0,
                Opcodes.PUSH to ZERO,
                Opcodes.RETURN to 0,
            )
        val first = attach(listOf(Handler(A, "onrightclick", 0)), route, a = button)
        attach(listOf(Handler(A, "onleftclick", 0)), POPS_AT_MOUSE, a = button)

        assertFalse("the right click pops nothing itself", first.rightClickMenu(button, isButton = true))
        assertTrue(rt.hasSkinMenu(button))
    }

    @Test
    fun `the menu of a xui group counts for the child it exposes, and so does a handler on the child`() {
        attach(listOf(Handler(A, "onrightbuttonup", 0)), POPS_AT_MOUSE, a = tab)
        assertTrue("a right click on the child fires on the group", rt.hasSkinMenu(trap))
        rt.scripts.clear()

        attach(listOf(Handler(A, "onrightclick", 0)), POPS_AT_MOUSE, a = trap)
        assertTrue(rt.hasSkinMenu(trap))
    }

    @Test
    fun `a right button handler without a menu is no skin menu`() {
        attach(listOf(Handler(A, "onrightbuttonup", 0)), listOf(Opcodes.PUSH to ZERO, Opcodes.RETURN to 0), a = layer)

        assertFalse(rt.hasSkinMenu(layer))
    }

    @Test
    fun `a menu bound to another object is not this object's`() {
        attach(listOf(Handler(A, "onrightbuttonup", 0)), POPS_AT_MOUSE, a = other)

        assertFalse(rt.hasSkinMenu(layer))
        assertTrue(rt.hasSkinMenu(other))
    }

    @Test
    fun `a menu only a timer reaches is not offered`() {
        val code =
            listOf(
                // 0: the right click starts the timer
                Opcodes.PUSH to TIMER,
                Opcodes.CALL to START,
                Opcodes.POP to 0,
                Opcodes.PUSH to ZERO,
                Opcodes.RETURN to 0,
            ) + POPS_AT_MOUSE
        attach(listOf(Handler(A, "onrightbuttonup", 0), Handler(TIMER, "ontimer", 5)), code, a = layer)

        assertFalse(rt.hasSkinMenu(layer))
    }

    @Test
    fun `detection runs no script and spends no steps`() {
        val code = listOf(Opcodes.PUSH to REC, Opcodes.CALL to HEARD, Opcodes.POP to 0) + POPS_AT_MOUSE
        attach(listOf(Handler(A, "onrightbuttonup", 0), Handler(B, "onrightclick", 0)), code, a = layer, b = button)
        val steps = rt.scriptSteps

        repeat(3) { assertTrue(rt.hasSkinMenu(layer)) }
        rt.pointerDown(layer.rect.left + 2, layer.rect.top + 2, seq = 4)

        assertEquals(4, rt.menuTarget)
        assertEquals(steps, rt.scriptSteps)
        assertTrue("no handler ran: $heard", heard.isEmpty())
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a script switched off offers no menu`() {
        val s = attach(listOf(Handler(A, "onrightbuttonup", 0)), POPS_AT_MOUSE, a = layer)
        // a run that fails every time (a POP on an empty stack) switches the script off after enough tries
        repeat(30) { s.run(2, emptyList(), "broken") }

        assertTrue(s.disabled)
        assertFalse(rt.hasSkinMenu(layer))
        rt.pointerDown(layer.rect.left + 2, layer.rect.top + 2, seq = 1)
        assertNull(rt.menuTarget)
    }

    private companion object {
        const val RECORDER = "recorder"

        // variables
        const val MENU = 0
        const val ZERO = 1
        const val REC = 2
        const val TIMER = 3
        const val CLASS = 4
        const val A = 6
        const val B = 7

        val METHODS =
            listOf(
                MethodRef(1, "popatmouse"),
                MethodRef(1, "popatxy"),
                MethodRef(0, "onleftclick"),
                MethodRef(0, "onrightbuttondown"),
                MethodRef(0, "onrightbuttonup"),
                MethodRef(0, "onrightclick"),
                MethodRef(2, "start"),
                MethodRef(2, "ontimer"),
                MethodRef(3, "heard"),
            )
        const val POPATMOUSE = 0
        const val POPATXY = 1
        const val ONLEFTCLICK = 2
        const val START = 6
        const val HEARD = 8

        /** A handler that pops the menu at the mouse and returns. */
        val POPS_AT_MOUSE =
            listOf(
                Opcodes.PUSH to MENU,
                Opcodes.CALL to POPATMOUSE,
                Opcodes.POP to 0,
                Opcodes.PUSH to ZERO,
                Opcodes.RETURN to 0,
            )
    }
}
