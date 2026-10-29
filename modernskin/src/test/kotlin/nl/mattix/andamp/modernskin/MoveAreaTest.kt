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
import nl.mattix.andamp.modernskin.skin.Edge
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Slider
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Where a drag moves the skin: a press anywhere but on a control is a press the host may turn into
 * moving the skin ([SkinRuntime.moveTarget]), as Winamp moves a window by its move areas.
 */
class MoveAreaTest {
    // a bar that moves the window, with a button on it; a panel group that moves it, holding a
    // background and a tab that says it does not; a title bar the skin defines, whose mousetrap
    // writes no move; a layer that writes nothing; a slider that says it moves; a layer a script
    // toggles on a tap, and a strip a script works as a slider by following the finger
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<groupdef id="panel">""" +
                        """<layer id="panel.bg" x="0" y="0" w="200" h="60"/>""" +
                        """<layer id="panel.tab" x="0" y="0" w="40" h="20" move="0"/>""" +
                        """</groupdef>""" +
                        """<groupdef id="wasabi.titlebar">""" +
                        """<layer id="mousetrap" x="0" y="0" w="0" h="0" relatw="1" relath="1"/>""" +
                        """</groupdef>""" +
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="300" h="300">""" +
                        """<layer id="bar" x="0" y="0" w="300" h="14" move="1"/>""" +
                        """<button id="close" x="280" y="2" w="10" h="10"/>""" +
                        """<group id="panel" x="0" y="50" w="200" h="60" move="1"/>""" +
                        """<group id="wasabi.titlebar" x="0" y="150" w="300" h="14"/>""" +
                        """<layer id="plain" x="0" y="200" w="100" h="30"/>""" +
                        """<slider id="volume" x="150" y="250" w="100" h="10" move="1"/>""" +
                        """<layer id="toggle" x="0" y="240" w="100" h="20"/>""" +
                        """<layer id="strip" x="150" y="200" w="100" h="10"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt = SkinRuntime(files, TestHost(files), FakePlayer()).apply { load() }

    private fun find(id: String): GuiObject = checkNotNull(checkNotNull(rt.mainLayout).findObject(id)) { id }

    /** A press at a point of [id], the touch [seq], then its release: the move target as the press left it. */
    private fun pressOn(
        id: String,
        seq: Int,
        dx: Int = 2,
        dy: Int = 2,
    ): Int? {
        val r = find(id).rect
        rt.pointerDown(r.left + dx, r.top + dy, seq)
        val target = rt.moveTarget
        rt.pointerUp(r.left + dx, r.top + dy)
        return target
    }

    /** What the scripts heard, in order: each event's name without its "on". */
    private val heard = ArrayList<String>()

    private val recorder =
        object : MakiObject {
            override val makiGuid = RECORDER

            override fun makiCall(
                name: String,
                args: List<Value>,
            ): Value = Value.Null.also { heard += name }
        }

    /** A script that records each of [events] on [target], all of them events with an x and a y. */
    private fun listen(
        target: GuiObject,
        vararg events: String,
    ) {
        val methods = ArrayList<MethodRef>()
        val code = ArrayList<Op>()
        val bindings =
            events.map { event ->
                val start = code.size
                repeat(2) { code += Op(Opcodes.POP, 0, -1, code.size) }
                methods += MethodRef(RECORDER_CLASS, event.removePrefix("on"))
                code += Op(Opcodes.PUSH, RECORDER_VAR, -1, code.size)
                code += Op(Opcodes.CALL, methods.lastIndex, 0, code.size)
                code += Op(Opcodes.POP, 0, -1, code.size)
                code += Op(Opcodes.PUSH, ZERO_VAR, -1, code.size)
                code += Op(Opcodes.RETURN, 0, -1, code.size)
                methods += MethodRef(TARGET_CLASS, event)
                Binding(TARGET_VAR, methods.lastIndex, start)
            }
        val program =
            MakiProgram(
                id = "listen",
                version = 0,
                classes = listOf(StdApi.guidOf("GuiObject")!!, RECORDER),
                methods = methods,
                variables =
                    listOf(
                        VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.Obj(target)),
                        VarDecl(Kind.OBJECT, guid = null, classVar = null, global = true, initial = Value.Obj(recorder)),
                        VarDecl(Kind.INT, guid = null, classVar = null, global = true, initial = Value.Int(0)),
                    ),
                bindings = bindings,
                code = code,
            )
        rt.scripts += Script(program, recorder, rt)
    }

    @Test
    fun `a press on an object that writes move="1" is a move target`() {
        assertEquals(1, pressOn("bar", seq = 1, dx = 100))
    }

    @Test
    fun `a button moves the window by a drag, and a drag let go of clicks nothing`() {
        assertEquals(2, pressOn("close", seq = 2))

        listen(find("close"), "onleftclick")
        val r = find("close").rect
        rt.pointerDown(r.left + 2, r.top + 2, seq = 20)
        assertEquals(20, rt.moveTarget)
        // the host takes the drag past its touch slop and lets the press go
        rt.pointerCancel()
        rt.pointerUp(r.left + 2, r.top + 2)
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun `a control that says it moves still works as a control`() {
        assertNull(pressOn("volume", seq = 3, dx = 50))
    }

    @Test
    fun `a drag on a slider moves the slider, not the skin`() {
        val volume = find("volume") as Slider
        val r = volume.rect
        val before = volume.position

        rt.pointerDown(r.left + 10, r.top + 5, seq = 1)
        rt.pointerMove(r.left + 80, r.top + 5)

        assertNull(rt.moveTarget)
        assertNotEquals("the thumb followed the finger", before, volume.position)
        rt.pointerUp(r.left + 80, r.top + 5)
    }

    @Test
    fun `an object inside a group that moves moves it too, and so does one that says it does not`() {
        assertEquals("the panel's background", 4, pressOn("panel.bg", seq = 4, dx = 100, dy = 40))
        assertEquals("the tab that writes move=0", 5, pressOn("panel.tab", seq = 5))
    }

    @Test
    fun `a wasabi title bar's mousetrap moves the window though it writes no move`() {
        assertNull("the title bar the skin defines writes none", find("mousetrap").move)

        assertEquals(6, pressOn("mousetrap", seq = 6, dx = 150))
    }

    @Test
    fun `a drag on a plain background, or on no object, moves the window`() {
        assertEquals(7, pressOn("plain", seq = 7))
        rt.pointerDown(290, 290, seq = 8)
        assertEquals(8, rt.moveTarget)
    }

    @Test
    fun `each press says it afresh, and a double tap is never one`() {
        assertEquals(9, pressOn("bar", seq = 9, dx = 100))
        assertNull(pressOn("volume", seq = 10, dx = 50))

        assertEquals(11, pressOn("bar", seq = 11, dx = 100))
        val r = find("bar").rect
        rt.doubleTap(r.left + 100, r.top + 2, seq = 12)
        assertNull(rt.moveTarget)
    }

    @Test
    fun `a layer a script toggles on a tap moves the window by a drag, and a tap still reaches the script`() {
        listen(find("toggle"), "onleftbuttondown", "onleftbuttonup")

        assertEquals(13, pressOn("toggle", seq = 13, dx = 50, dy = 10))
        assertEquals(listOf("leftbuttondown", "leftbuttonup"), heard)
    }

    @Test
    fun `a strip a script follows the finger across takes the drag, and moves nothing`() {
        val strip = find("strip")
        listen(strip, "onleftbuttondown", "onmousemove", "onleftbuttonup")
        val r = strip.rect

        rt.pointerDown(r.left + 10, r.top + 5, seq = 14)
        rt.pointerMove(r.left + 60, r.top + 5)
        rt.pointerUp(r.left + 60, r.top + 5)

        assertNull(rt.moveTarget)
        assertEquals(listOf("leftbuttondown", "mousemove", "leftbuttonup"), heard)
    }

    @Test
    fun `Winamp Modern moves by its title bar, its background and its Play button`() {
        val wal = ZipSkinFiles(File(localResource("/WinampModern566.wal").toURI()))
        val modern = SkinRuntime(wal, TestHost(wal), FakePlayer()).apply { load() }
        // the margin a Pixel 9 Pro gives at Winamp Modern's 2x: 24 dp at 2.25 px a dp
        modern.touchSlop = 27
        val main = checkNotNull(modern.mainLayout)

        // the middle of the title bar, where the Pixel's taps landed
        modern.pointerDown(172, 7, seq = 1)
        assertEquals("the title bar", 1, modern.moveTarget)
        modern.pointerUp(172, 7)

        val play = modern.allObjects.first { it.id == "play" && it.layoutObject === main }.rect
        modern.pointerDown(play.left + play.width / 2, play.top + play.height / 2, seq = 2)
        assertEquals("the Play button", 2, modern.moveTarget)
        modern.pointerUp(play.left + play.width / 2, play.top + play.height / 2)

        // the panel above the clock
        modern.pointerDown(WM_PANEL.first, WM_PANEL.second, seq = 3)
        assertEquals("the panel", 3, modern.moveTarget)
    }

    @Test
    fun `MMD3 moves by its body, and its knobs turn by a drag`() {
        val wal = ZipSkinFiles(File(localResource("/MMD3.wal").toURI()))
        val mmd3 = SkinRuntime(wal, TestHost(wal), FakePlayer()).apply { load() }
        mmd3.touchSlop = 24
        val main = checkNotNull(mmd3.mainLayout)

        mmd3.pointerDown(MMD3_BODY.first, MMD3_BODY.second, seq = 1)
        assertEquals("the body", 1, mmd3.moveTarget)
        mmd3.pointerUp(MMD3_BODY.first, MMD3_BODY.second)

        // each knob's script reads where the mouse is while the button is down on it
        for ((seq, id) in listOf("volumeanim", "volumeanim2", "bassanim", "trebleanim").withIndex()) {
            val knob = mmd3.allObjects.first { it.id.equals(id, ignoreCase = true) && it.layoutObject === main }.rect
            val x = knob.left + knob.width / 2
            val y = knob.top + knob.height / 2
            mmd3.pointerDown(x, y, seq + 2)
            assertNull(id, mmd3.moveTarget)
            mmd3.pointerUp(x, y)
        }
    }

    /** CornerAmp, with the host answering its menus with [answer], in a 320 by 633 view, in the top left corner. */
    private fun cornerAmp(answer: () -> Int): SkinRuntime {
        val wal = ZipSkinFiles(File(localResource("/CornerAmp_Redux.wal").toURI()))
        val corner =
            object : SkinHost by TestHost(wal) {
                override fun chooseFromMenu(menu: SkinMenu): Int = answer()
            }
        return SkinRuntime(wal, corner, FakePlayer()).apply {
            privateInts["corner/lastpos"] = 1
            load()
            viewSize = 320 to 633
            touchSlop = 24
        }
    }

    @Test
    fun `CornerAmp moves by its body, and its seek and volume strips take their drags`() {
        val corner = cornerAmp { 0 }
        val main = checkNotNull(corner.mainLayout)

        // the quarter disc, clear of its buttons
        corner.pointerDown(150, 150, seq = 1)
        assertEquals("the body", 1, corner.moveTarget)
        corner.pointerUp(150, 150)

        for ((seq, id) in listOf(2 to "seek1", 3 to "volume1")) {
            val strip = checkNotNull(main.findObject(id)).rect
            corner.pointerDown(strip.left + 3, strip.top + 3, seq)
            assertNull(id, corner.moveTarget)
            corner.pointerUp(strip.left + 3, strip.top + 3)
        }
    }

    @Test
    fun `a corner picked from CornerAmp's menu counts as the skin placing itself, its start and a dismissed menu do not`() {
        var answer = 0
        val corner = cornerAmp { answer }
        val main = checkNotNull(corner.mainLayout)
        assertEquals("the start puts it in its corner", Edge.START to Edge.START, main.edgeX to main.edgeY)
        assertEquals(0, corner.edgePlacements)

        // a long press on the title it shows opens its menu, dismissed and then picked: the same
        // corner, then another
        for ((seq, picked) in listOf(0, 1, 4).withIndex()) {
            answer = picked
            val title = main.children.first { it.id.startsWith("title") && it.visible }
            corner.pointerDown(title.rect.left + 3, title.rect.top + 3, seq)
            corner.pointerCancel()
            corner.rightClick(seq)
        }

        assertEquals("the same corner, and the bottom right", 2, corner.edgePlacements)
        assertEquals(Edge.END to Edge.END, main.edgeX to main.edgeY)
    }

    @Test
    fun `a script that moves the main window against an edge on a tap is not the skin placing itself`() {
        val main = checkNotNull(rt.mainLayout)
        rt.viewSize = 320 to 633

        // as MMD3 puts its shade bar against the top edge when a button switches to it
        rt.gesture(fresh = true) { main.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(300), Value.Int(300))) }

        assertEquals(Edge.START to Edge.START, main.edgeX to main.edgeY)
        assertEquals(0, rt.edgePlacements)
    }

    private companion object {
        const val RECORDER = "recorder"
        const val TARGET_CLASS = 0
        const val RECORDER_CLASS = 1
        const val TARGET_VAR = 0
        const val RECORDER_VAR = 1
        const val ZERO_VAR = 2

        /** A point on the panel left of Winamp Modern's visualizer, above its clock: no control. */
        val WM_PANEL = 60 to 45

        /** A point on MMD3's main window between its shuffle button and its display: no control. */
        val MMD3_BODY = 75 to 60
    }
}
