// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.Button
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.GuiObject
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Slider
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
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

    @Test
    fun everyControlIsReachable() {
        for (wal in listOf("WinampModern566.wal", "MMD3.wal", "CornerAmp_Redux.wal")) {
            val unreachable = areas(wal).filterValues { it[0] == 0 }.keys
            assertTrue("$wal: no finger reaches $unreachable", unreachable.isEmpty())
        }
    }
}
