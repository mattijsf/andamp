// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.Button
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Slider
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a finger reaches each control, with the margin the app sets on a Pixel 9 Pro (24 layout
 * pixels: the skin is drawn 3x on a 3x screen). Every visible control must be reachable, and a
 * small one should answer across about 48 dp.
 */
class TapAreaTest {
    private fun areas(wal: String): Map<GuiObject, IntArray> {
        val files = ZipSkinFiles(File(localResource("/$wal").toURI()))
        val rt = SkinRuntime(files, TestHost(files), FakePlayer())
        rt.load()
        repeat(5) { rt.tick() }
        rt.draw()
        rt.touchSlop = 24
        val (w, h) = checkNotNull(rt.mainLayout).size
        // per control: points it answers, and the box they span (x0, y0, x1, y1)
        val found = LinkedHashMap<GuiObject, IntArray>()
        val exposed = HashSet<GuiObject>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                rt.hitTest(x, y)?.let { exposed.add(it) }
                val t = rt.touchTarget(x, y) ?: continue
                val a = found.getOrPut(t) { intArrayOf(0, Int.MAX_VALUE, Int.MAX_VALUE, -1, -1) }
                a[0]++
                a[1] = minOf(a[1], x)
                a[2] = minOf(a[2], y)
                a[3] = maxOf(a[3], x)
                a[4] = maxOf(a[4], y)
            }
        }
        // controls a mouse could click somewhere: on top at some point of the window
        val controls = rt.allObjects.filter { (it is Button || it is Slider) && it in exposed }
        println("$wal: ${controls.size} visible controls")
        for (c in controls.sortedBy { found[it]?.let { a -> minOf(a[3] - a[1], a[4] - a[2]) } ?: -1 }.take(12)) {
            val a = found[c]
            val box = a?.let { "${it[3] - it[1] + 1}x${it[4] - it[2] + 1} (${it[0]} px)" } ?: "UNREACHABLE"
            println("  $c ${c.rect.width}x${c.rect.height} -> $box")
        }
        return controls.associateWith { found[it] ?: intArrayOf(0) }
    }

    /**
     * Winamp Modern's config drawer sets its tabs a few pixels under the EQ sliders. Drawn 2x on a
     * 3x screen the margin is 36 layout pixels, which reaches from a tab's label up to the sliders:
     * a tap on a label still switches the tab, and the sliders still move by a drag.
     */
    @Test
    fun winampModernDrawerTabsTakeTaps() {
        val files = ZipSkinFiles(File(localResource("/WinampModern566.wal").toURI()))
        val player = FakePlayer()
        val rt = SkinRuntime(files, TestHost(files), player)
        var now = 0L
        rt.clock = { now }
        player.clock = { now }
        rt.load()

        fun settle() =
            repeat(SETTLE_FRAMES) {
                now += FRAME_MS
                rt.tick()
            }

        fun tap(o: GuiObject) {
            val origin = rt.windowRect(checkNotNull(o.layoutObject))
            val x = origin.left + o.rect.left + o.rect.width / 2
            val y = origin.top + o.rect.top + o.rect.height / 2
            rt.pointerDown(x, y)
            rt.pointerUp(x, y)
            settle()
        }

        fun shown(o: GuiObject) = o.visible && generateSequence(o.parent) { it.parent }.all { it.visible }
        val main = checkNotNull(rt.mainLayout)
        settle()
        tap(checkNotNull(main.findObject("drawer.button.open")))
        rt.touchSlop = 36
        val pages =
            listOf(
                "eq",
                "options",
                "colorthemes",
            ).associateWith { checkNotNull(main.findObject("player.normal.drawer.$it")) }
        val bands = rt.allObjects.filterIsInstance<Slider>().filter { it.action.equals("eq_band", true) }
        val positions = bands.map { it.position }
        assertTrue("the drawer opens on the equalizer", shown(pages.getValue("eq")))
        // from the equalizer to each other tab, as the sliders sit right above them there
        for (tab in listOf("colorthemes", "eq", "options")) {
            val trap = rt.allObjects.single { it.id.equals("mousetrapTab$tab", true) && shown(it) }
            tap(trap)
            assertTrue("$tab shows after a tap on its label", shown(pages.getValue(tab)))
            assertTrue("one page shows: ${pages.filterValues(::shown).keys}", pages.values.count(::shown) == 1)
        }
        assertEquals("no EQ band moved", positions, bands.map { it.position })

        // back on the equalizer, a drag up a band's track moves that band, not the skin
        tap(rt.allObjects.single { it.id.equals("mousetrapTabEQ", true) && shown(it) })
        val band = bands.first { shown(it) }
        val before = band.position
        val origin = rt.windowRect(checkNotNull(band.layoutObject))
        val x = origin.left + band.rect.left + band.rect.width / 2
        val y = origin.top + band.rect.top + band.rect.height - 4
        rt.pointerDown(x, y, seq = 1)
        rt.pointerMove(x, y - band.rect.height / 2)
        rt.pointerUp(x, y - band.rect.height / 2)
        assertNull("the band took the drag", rt.moveTarget)
        assertTrue("the band moved: ${band.position}", band.position != before)
    }

    @Test
    fun everyControlIsReachable() {
        for (wal in listOf("WinampModern566.wal", "MMD3.wal", "CornerAmp_Redux.wal")) {
            val unreachable = areas(wal).filterValues { it[0] == 0 }.keys
            assertTrue("$wal: no finger reaches $unreachable", unreachable.isEmpty())
        }
    }

    private companion object {
        const val FRAME_MS = 16L

        /** Two seconds of frames: long enough for the drawer to slide open and a tab to switch. */
        const val SETTLE_FRAMES = 125
    }
}
