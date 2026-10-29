// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A drag on a window's resize grip resizes the window, as in Winamp, within its limits and the phone's view. */
class ResizeGripTest {
    // a main window 200 by 100, at least 150 by 80, with a corner grip at its bottom right and a
    // plain layer to drag elsewhere
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1">""" +
                        """<layout id="normal" w="200" h="100" minimum_w="150" minimum_h="80">""" +
                        """<layer id="body" x="0" y="0" w="180" h="80"/>""" +
                        """<layer id="grip" x="-10" y="-10" w="10" h="10" relatx="1" relaty="1" resize="bottomright"/>""" +
                        """</layout></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0">""" +
                        """<layout id="normal" w="200" h="60" minimum_w="100" minimum_h="40">""" +
                        """<layer id="pl.grip" x="-10" y="-10" w="10" h="10" relatx="1" relaty="1" resize="bottomright"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val rt =
        SkinRuntime(files, TestHost(files), FakePlayer().apply { clock = { 0L } }).apply {
            clock = { 0L }
            viewSize = 400 to 300
            touchSlop = 2
            load()
        }

    private val main get() = checkNotNull(rt.mainLayout)

    private fun drag(
        fromX: Int,
        fromY: Int,
        dx: Int,
        dy: Int,
    ) {
        rt.ensureLayout()
        val at = rt.windowRect(main)
        rt.pointerDown(at.left + fromX, at.top + fromY, seq = 1)
        rt.pointerMove(at.left + fromX + dx / 2, at.top + fromY + dy / 2)
        rt.pointerMove(at.left + fromX + dx, at.top + fromY + dy)
        rt.pointerUp(at.left + fromX + dx, at.top + fromY + dy)
    }

    @Test
    fun `a drag on the grip resizes the window by as much as the finger traveled, and moves no window`() {
        rt.ensureLayout()
        val at = rt.windowRect(main)
        rt.pointerDown(at.left + 195, at.top + 95, seq = 1)
        assertNull("a drag from the grip does not move the window", rt.moveTarget)
        rt.pointerMove(at.left + 245, at.top + 135)
        rt.pointerUp(at.left + 245, at.top + 135)

        assertEquals(250 to 140, main.size)
    }

    @Test
    fun `the window keeps to its minimum and to the phone's view`() {
        drag(195, 95, 1000, 1000)
        assertEquals(400 to 300, main.size)

        drag(395, 295, -1000, -1000)
        assertEquals(150 to 80, main.size)
    }

    @Test
    fun `a drag anywhere else leaves the size`() {
        drag(50, 40, 60, 30)

        assertEquals(200 to 100, main.size)
    }

    @Test
    fun `a docked window resized by its grip keeps the size it was given as the stack is laid out again`() {
        rt.openWindow(rt.containers.first { it.id == "pl" })
        rt.ensureLayout()
        val pl = rt.windows.last()
        val at = rt.windowRect(pl)
        rt.pointerDown(at.right - 5, at.bottom - 5, seq = 1)
        rt.pointerMove(at.right - 45, at.bottom + 35)
        rt.pointerUp(at.right - 45, at.bottom + 35)

        rt.relayout()
        rt.ensureLayout()

        assertEquals(160 to 100, pl.size)
        assertEquals(160 to 100, rt.windowRect(pl).let { it.width to it.height })
    }
}
