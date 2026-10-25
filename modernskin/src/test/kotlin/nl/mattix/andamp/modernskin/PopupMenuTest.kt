// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.Button
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.PopupMenu
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A script's popup menu reaches the host whole, and only in reply to a touch, a few times at most. */
class PopupMenuTest {
    // one window with a Play button
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    /** The menus the host was asked to show; it answers [pick]. */
    private val shown = ArrayList<SkinMenu>()
    private var pick: Int? = 7

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
                return pick
            }
        }

    private val player = FakePlayer()
    private val rt = SkinRuntime(files, host, player)

    private fun PopupMenu.call(
        name: String,
        vararg args: Value,
    ) = makiCall(name, args.toList())

    private fun PopupMenu.command(
        text: String,
        id: Int,
        checked: Boolean = false,
        disabled: Boolean = false,
    ) = call("addcommand", Value.Str(text), Value.Int(id), Value.Bool(checked), Value.Bool(disabled))

    private fun PopupMenu.pop(): Int = call("popatmouse")!!.asInt

    @Test
    fun `the host gets commands, marks, separators and submenus, and the script hears its pick`() {
        val sub = PopupMenu(rt).apply { command("Inner", 20) }
        val menu =
            PopupMenu(rt).apply {
                command("Play", 1)
                command("Shuffle", 2, checked = true)
                call("addseparator")
                command("Gone", 3)
                call("disablecommand", Value.Int(3), Value.Bool(true))
                call("checkcommand", Value.Int(1), Value.Bool(true))
                call("addsubmenu", Value.Obj(sub), Value.Str("More"))
            }

        val answer = rt.gesture(fresh = true) { menu.pop() }

        assertEquals(7, answer)
        val items = shown.single().items
        assertEquals(5, items.size)
        val play = items[0] as SkinMenu.Command
        assertEquals(listOf(1, true, false), listOf(play.id, play.checked, play.disabled))
        assertTrue((items[1] as SkinMenu.Command).checked)
        assertEquals(SkinMenu.Separator, items[2])
        val gone = items[3] as SkinMenu.Command
        assertTrue(gone.disabled)
        val more = items[4] as SkinMenu.Submenu
        assertEquals("More", more.text)
        assertEquals(20, (more.menu.items.single() as SkinMenu.Command).id)
    }

    @Test
    fun `words show without a keyboard shortcut or the marks that underline a letter`() {
        val sub = PopupMenu(rt).apply { command("Inner", 20) }
        val menu =
            PopupMenu(rt).apply {
                command("Start/Stop plug-in\tCtrl+Shift+K", 1)
                command("&Play", 2)
                command("Rock && Roll", 3)
                call("addsubmenu", Value.Obj(sub), Value.Str("&More\tAlt+M"))
            }

        rt.gesture(fresh = true) { menu.pop() }

        val items = shown.single().items
        assertEquals(
            listOf("Start/Stop plug-in", "Play", "Rock & Roll"),
            items.filterIsInstance<SkinMenu.Command>().map { it.text },
        )
        assertEquals("More", (items[3] as SkinMenu.Submenu).text)
    }

    @Test
    fun `a menu popped outside a touch, as from a timer, is not shown and answers 0`() {
        val menu = PopupMenu(rt).apply { command("Play", 1) }

        assertEquals(0, menu.pop())
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `one touch shows at most three menus, and the next touch starts over`() {
        val menu = PopupMenu(rt).apply { command("Play", 1) }

        val answers = rt.gesture(fresh = true) { List(5) { menu.pop() } }
        assertEquals(listOf(7, 7, 7, 0, 0), answers)
        assertEquals(3, shown.size)

        assertEquals(7, rt.gesture(fresh = true) { menu.pop() })
        assertEquals(4, shown.size)
    }

    @Test
    fun `a menu closed without the listener answers 0 and ends the touch's menus`() {
        val menu = PopupMenu(rt).apply { command("Play", 1) }
        pick = null

        val answers = rt.gesture(fresh = true) { List(2) { menu.pop() } }

        assertEquals(listOf(0, 0), answers)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a menu during a press lets go of the button, with no click or long press after it`() {
        rt.load()
        val button = checkNotNull(rt.mainLayout?.findObject("play")) as Button
        val menu = PopupMenu(rt).apply { command("Play", 1) }
        rt.pointerDown(15, 15)
        assertTrue(button.pressed)

        rt.gesture { menu.pop() }
        rt.pointerUp(15, 15)
        // the host's long press: it takes the touch, then right-clicks the press's object
        rt.pointerCancel()
        rt.rightClick(0)

        assertEquals(false, button.pressed)
        assertEquals(Player.Status.STOPPED, player.status)
        // the next touch clicks as usual
        rt.pointerDown(15, 15)
        rt.pointerUp(15, 15)
        assertEquals(Player.Status.PLAYING, player.status)
    }

    @Test
    fun `a wide menu that holds itself stays small`() {
        val menu = PopupMenu(rt).apply { command("Play".repeat(100), 1) }
        repeat(20) { menu.call("addsubmenu", Value.Obj(menu), Value.Str("Again")) }

        rt.gesture(fresh = true) { menu.pop() }

        fun count(m: SkinMenu): Int = m.items.sumOf { 1 + ((it as? SkinMenu.Submenu)?.let { s -> count(s.menu) } ?: 0) }
        val total = count(shown.single())
        assertTrue("items: $total", total in 100..512)
        assertTrue((shown.single().items[0] as SkinMenu.Command).text.length <= 128)
    }

    @Test
    fun `a menu that holds itself ends a few levels down`() {
        val menu = PopupMenu(rt).apply { command("Play", 1) }
        menu.call("addsubmenu", Value.Obj(menu), Value.Str("Again"))

        rt.gesture(fresh = true) { menu.pop() }

        var levels = 0
        var at: SkinMenu? = shown.single()
        while (at != null) {
            levels++
            at =
                at.items
                    .filterIsInstance<SkinMenu.Submenu>()
                    .firstOrNull()
                    ?.menu
        }
        assertTrue("levels: $levels", levels in 2..16)
    }
}
