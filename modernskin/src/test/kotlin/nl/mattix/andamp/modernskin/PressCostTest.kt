// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.Layout
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a press costs: no layout pass for the focus, and the skin menu's objects found once per layout. */
class PressCostTest {
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><elements><bitmap id="on" file="on.png"/>""" +
                        """<bitmap id="off" file="off.png"/></elements><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<layer id="title" x="0" y="0" w="200" h="14" image="on" inactiveimage="off"/>""" +
                        """<button id="close" x="180" y="2" w="10" h="10" activealpha="255" inactivealpha="0"/>""" +
                        """<button id="go" x="10" y="40" w="20" h="20"/><button id="next" x="40" y="40" w="20" h="20"/>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="pl" default_visible="0"><layout id="normal" w="200" h="80">""" +
                        """<button id="row" x="10" y="10" w="100" h="20"/></layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host =
        object : SkinHost {
            override fun imageSize(file: String): Pair<Int, Int>? = 200 to 14

            override fun textWidth(
                text: String,
                font: FontDef?,
                size: Int,
            ) = text.length * 6

            override fun log(message: String) = Unit

            override fun invalidate() = Unit
        }

    private val rt =
        SkinRuntime(files, host, FakePlayer()).apply {
            load()
            viewSize = 200 to 400
            touchSlop = 10
        }

    private val main: Layout get() = checkNotNull(rt.mainLayout)
    private val pl: Layout get() = checkNotNull(rt.container("pl")?.current)

    private fun GuiObject.center(): Pair<Int, Int> {
        val origin = rt.windowRect(checkNotNull(layoutObject))
        return origin.left + rect.left + rect.width / 2 to origin.top + rect.top + rect.height / 2
    }

    private fun tap(o: GuiObject) {
        val (x, y) = o.center()
        rt.pointerDown(x, y)
        rt.pointerUp(x, y)
    }

    /** The main window's title bar picture and its close button's alpha, as the frame drawn now shows them. */
    private fun mainLooks(): Pair<String, Int> {
        val cmds =
            rt
                .drawWindows()
                .first()
                .second
                .filterIsInstance<DrawCmd.Image>()
        val title = cmds.first { it.dst.top == 0 && it.dst.width == 200 }
        return title.bitmap.id to checkNotNull(main.findObject("close")).effectiveAlpha
    }

    @Test
    fun `the first press after load runs no layout pass`() {
        rt.drawWindows()
        val passes = rt.layoutPasses

        tap(checkNotNull(main.findObject("go")))

        assertEquals(passes, rt.layoutPasses)
    }

    @Test
    fun `a press that moves the focus to another window runs no layout pass`() {
        rt.openWindow(checkNotNull(rt.container("pl")))
        rt.drawWindows()
        val passes = rt.layoutPasses

        tap(checkNotNull(pl.findObject("row")))
        assertTrue(rt.hasFocus(pl))
        tap(checkNotNull(main.findObject("go")))
        assertTrue(rt.hasFocus(main))

        assertEquals(passes, rt.layoutPasses)
    }

    @Test
    fun `a window losing focus shows its inactive alpha and image without a layout pass`() {
        rt.openWindow(checkNotNull(rt.container("pl")))
        assertEquals("on" to 255, mainLooks())
        val passes = rt.layoutPasses

        tap(checkNotNull(pl.findObject("row")))
        assertEquals("off" to 0, mainLooks())
        tap(checkNotNull(main.findObject("go")))
        assertEquals("on" to 255, mainLooks())

        assertEquals(passes, rt.layoutPasses)
    }

    @Test
    fun `finding the skin menu under a press reads each object's bindings at most once per layout`() {
        rt.drawWindows()
        val go = checkNotNull(main.findObject("go"))
        val next = checkNotNull(main.findObject("next"))
        val before = rt.skinMenuReads

        tap(go)
        val first = rt.skinMenuReads - before
        assertTrue("the objects within reach are read: $first", first > 0)
        tap(go)
        tap(next)
        tap(go)
        val all = rt.skinMenuReads - before
        assertTrue("each object at most once: $all of ${rt.windows.size} windows' objects", all <= first + 2)

        rt.relayout()
        rt.drawWindows()
        tap(go)
        assertEquals("a layout pass reads them again", all + first, rt.skinMenuReads - before)
    }
}
