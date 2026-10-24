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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The right click a host sends for a touch (a long press, then "Skin menu"): on the object with a
 * skin menu that touch landed on, at the point it landed, as a separate touch whose menus show.
 */
class RightClickTest {
    // a button and a layer with menus, a plain Play button, a vis with a second Play button beside
    // it, and a shade layout to switch to
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="200">""" +
                        """<button id="button" x="10" y="10" w="20" h="20"/>""" +
                        """<layer id="layer" x="50" y="10" w="20" h="20"/>""" +
                        """<button id="play" action="PLAY" x="10" y="150" w="20" h="20"/>""" +
                        """<vis id="vis" x="100" y="100" w="60" h="30"/>""" +
                        """<button id="near" action="PLAY" x="165" y="100" w="20" h="20"/>""" +
                        """</layout><layout id="shade" w="300" h="20"/></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val shown = ArrayList<SkinMenu>()

    /** What the host answers for a menu; null is a menu closed without the listener. */
    private var answer: Int? = PICK

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

            override fun chooseFromMenu(menu: SkinMenu): Int? {
                shown += menu
                return answer
            }
        }

    private val player = FakePlayer()
    private val rt = SkinRuntime(files, host, player)

    /** What the handlers recorded, in order: the recorder method's name, and the argument of "answer". */
    private val heard = ArrayList<String>()

    private val recorder =
        object : MakiObject {
            override val makiGuid = RECORDER

            override fun makiCall(
                name: String,
                args: List<Value>,
            ): Value = Value.Null.also { heard += if (name == "answer") "answer ${args[0].asInt}" else name }
        }

    private lateinit var button: GuiObject
    private lateinit var layer: GuiObject
    private lateinit var play: GuiObject
    private lateinit var vis: GuiObject
    private lateinit var near: GuiObject
    private lateinit var menu: PopupMenu

    @Before
    fun load() {
        rt.load()
        val main = checkNotNull(rt.mainLayout)
        button = checkNotNull(main.findObject("button"))
        layer = checkNotNull(main.findObject("layer"))
        play = checkNotNull(main.findObject("play"))
        vis = checkNotNull(main.findObject("vis"))
        near = checkNotNull(main.findObject("near"))
        menu = PopupMenu(rt).apply { makiCall("addcommand", listOf(Value.Str("Item"), Value.Int(PICK))) }
    }

    /** One handler: its event and its code, a list of steps from [record], [pop] and [popAndAnswer]. */
    private class Handler(
        val event: String,
        val steps: List<List<Pair<Int, Int>>>,
    )

    /** Calls the recorder method [method]. */
    private fun record(method: Int) = listOf(Opcodes.PUSH to REC, Opcodes.CALL to method, Opcodes.POP to 0)

    /** Pops the menu and drops the pick. */
    private val pop = listOf(Opcodes.PUSH to MENU, Opcodes.CALL to POPATMOUSE, Opcodes.POP to 0)

    /** Pops the menu and records the pick as "answer <id>". */
    private val popAndAnswer =
        listOf(Opcodes.PUSH to REC, Opcodes.PUSH to MENU, Opcodes.CALL to POPATMOUSE, Opcodes.CALL to ANSWER, Opcodes.POP to 0)

    /** A script holding [target] with [handlers]; the handlers with x and y drop them first. */
    private fun attach(
        target: MakiObject,
        vararg handlers: Handler,
    ) {
        val code = ArrayList<Pair<Int, Int>>()
        val bindings =
            handlers.map { h ->
                val start = code.size
                if (h.event != "onrightclick") repeat(2) { code += Opcodes.POP to 0 }
                h.steps.forEach { code += it }
                code += Opcodes.PUSH to ZERO
                code += Opcodes.RETURN to 0
                Binding(TARGET, METHODS.indexOfFirst { it.name == h.event }, start)
            }

        fun obj(v: MakiObject) = VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.Obj(v))
        val program =
            MakiProgram(
                id = "rightclick",
                version = 0,
                classes = listOf(StdApi.guidOf("GuiObject")!!, StdApi.guidOf("PopupMenu")!!, RECORDER),
                methods = METHODS,
                variables =
                    listOf(
                        obj(target),
                        obj(menu),
                        obj(recorder),
                        VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0)),
                    ),
                bindings = bindings,
                code =
                    code.mapIndexed { i, (op, arg) ->
                        val argc =
                            when {
                                op != Opcodes.CALL || arg == POPATMOUSE -> -1
                                arg == ANSWER -> 1
                                else -> 0
                            }
                        Op(op, arg, argc, i)
                    },
            )
        rt.scripts += Script(program, recorder, rt)
    }

    /** The three right-click events, each recorded, the release also popping the menu. */
    private fun recordAll(target: GuiObject) =
        attach(
            target,
            Handler("onrightbuttondown", listOf(record(DOWN))),
            Handler("onrightbuttonup", listOf(record(UP), pop)),
            Handler("onrightclick", listOf(record(CLICK))),
        )

    private fun press(
        o: GuiObject,
        seq: Int,
    ) = rt.pointerDown(o.rect.left + 2, o.rect.top + 2, seq)

    @Test
    fun `on a Button the right button goes down and up, then onRightClick, and on a layer only the first two`() {
        recordAll(button)
        recordAll(layer)

        press(button, seq = 1)
        rt.pointerCancel()
        rt.rightClick(1)
        assertEquals(listOf("down", "up", "click"), heard)

        heard.clear()
        press(layer, seq = 2)
        rt.pointerCancel()
        rt.rightClick(2)
        assertEquals(listOf("down", "up"), heard)
    }

    @Test
    fun `a menu shows even after the host's cancel ended the press`() {
        attach(layer, Handler("onrightbuttonup", listOf(pop)))

        press(layer, seq = 1)
        rt.pointerCancel()
        rt.rightClick(1)

        assertEquals(1, shown.size)
    }

    @Test
    fun `a right click is a new touch, whatever menus the press showed`() {
        // the menu the press opens closes without the listener: that touch shows no more (an
        // object with a menu hears the press once the finger moves beyond reach)
        attach(layer, Handler("onleftbuttondown", listOf(pop)), Handler("onrightbuttonup", listOf(pop)))
        answer = null
        press(layer, seq = 1)
        rt.pointerMove(layer.rect.left + 12, layer.rect.top + 2)
        assertEquals(1, shown.size)
        rt.pointerCancel()
        answer = PICK
        rt.rightClick(1)
        assertEquals(2, shown.size)

        // the press shows three menus, as many as one touch may
        attach(button, Handler("onleftbuttondown", List(3) { pop }), Handler("onrightclick", listOf(pop)))
        press(button, seq = 2)
        rt.pointerMove(button.rect.left + 12, button.rect.top + 2)
        assertEquals(5, shown.size)
        rt.pointerCancel()
        rt.rightClick(2)
        assertEquals(6, shown.size)
    }

    @Test
    fun `three menus in a row show and a fourth answers 0`() {
        attach(button, Handler("onrightclick", List(4) { popAndAnswer }))

        press(button, seq = 1)
        rt.pointerCancel()
        rt.rightClick(1)

        assertEquals(3, shown.size)
        assertEquals(listOf("answer $PICK", "answer $PICK", "answer $PICK", "answer 0"), heard)
    }

    @Test
    fun `an older touch's seq does nothing, nor does an object no longer on screen`() {
        recordAll(layer)
        press(layer, seq = 1)
        press(layer, seq = 2)

        rt.rightClick(1)
        assertTrue("the touch before is stale: $heard", heard.isEmpty())

        checkNotNull(rt.mainContainer).switchTo("shade")
        rt.rightClick(2)
        assertTrue("the layer went with its layout: $heard", heard.isEmpty())
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a vis with a menu under the finger wins over a button with a menu beside it`() {
        attach(vis, Handler("onrightbuttonup", listOf(record(UP), pop)))
        attach(near, Handler("onrightclick", listOf(record(CLICK), pop)))
        rt.touchSlop = 24
        // inside the vis, 7 px from the button: the tap goes to the button, the menu to the vis
        val x = near.rect.left - 7
        val y = vis.rect.top + 10
        assertEquals(near, rt.touchTarget(x, y))

        rt.pointerDown(x, y, seq = 3)
        rt.pointerCancel()
        assertEquals(3, rt.menuTarget)
        rt.rightClick(3)

        assertEquals(listOf("up"), heard)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a touch beside an object with a menu, within reach, aims at it`() {
        attach(layer, Handler("onrightbuttonup", listOf(pop)))
        rt.touchSlop = 12

        rt.pointerDown(layer.rect.left - 5, layer.rect.top + 5, seq = 6)
        assertEquals(6, rt.menuTarget)
        rt.pointerDown(layer.rect.left - 20, layer.rect.top + 5, seq = 7)
        assertNull("out of reach", rt.menuTarget)
    }

    @Test
    fun `menuTarget names the touch on an object with a menu and is null on a plain button`() {
        attach(layer, Handler("onrightbuttonup", listOf(pop)))

        press(layer, seq = 4)
        assertEquals(4, rt.menuTarget)

        press(play, seq = 5)
        assertNull(rt.menuTarget)
        rt.rightClick(5)
        rt.rightClick(4)
        assertTrue("neither seq opens a menu now", shown.isEmpty())

        rt.doubleTap(layer.rect.left + 2, layer.rect.top + 2, seq = 8)
        assertEquals("a double tap aims too", 8, rt.menuTarget)
        assertEquals(Player.Status.STOPPED, player.status)
    }

    @Test
    fun `a right click lets go of a press still held, so its release gives no click`() {
        // a menu behind a condition that does not hold: the vis counts as having one, none pops
        // (the handler is the script's only one: 2 pops of x and y, 3 steps of record, then this,
        // and the jump lands on the closing PUSH at 10)
        val skipped = listOf(Opcodes.PUSH to ZERO, Opcodes.JUMP_IF_NOT to 10) + pop
        attach(vis, Handler("onrightbuttonup", listOf(record(UP), skipped)))
        rt.touchSlop = 24
        // inside the vis, 7 px from the Play button: the button has the press, the menu aims at the vis
        val x = near.rect.left - 7
        val y = vis.rect.top + 10
        val button = near as Button

        rt.pointerDown(x, y, seq = 1)
        assertTrue(button.pressed)
        rt.rightClick(1)
        rt.pointerUp(x, y)

        assertEquals(listOf("up"), heard)
        assertTrue(shown.isEmpty())
        assertEquals(Player.Status.STOPPED, player.status)
        assertFalse(button.pressed)
    }

    private companion object {
        const val RECORDER = "recorder"
        const val PICK = 7

        // variables
        const val TARGET = 0
        const val MENU = 1
        const val REC = 2
        const val ZERO = 3

        val METHODS =
            listOf(
                MethodRef(1, "popatmouse"),
                MethodRef(0, "onrightbuttondown"),
                MethodRef(0, "onrightbuttonup"),
                MethodRef(0, "onrightclick"),
                MethodRef(2, "down"),
                MethodRef(2, "up"),
                MethodRef(2, "click"),
                MethodRef(2, "answer"),
                MethodRef(0, "onleftbuttondown"),
            )
        const val POPATMOUSE = 0
        const val DOWN = 4
        const val UP = 5
        const val CLICK = 6
        const val ANSWER = 7
    }
}
