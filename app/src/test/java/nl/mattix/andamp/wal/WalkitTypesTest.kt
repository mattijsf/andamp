// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.Edge
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.modernskin.skin.WindowId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import nl.mattix.walkit.BitmapDef as WkBitmapDef
import nl.mattix.walkit.DrawCmd as WkDrawCmd
import nl.mattix.walkit.Edge as WkEdge
import nl.mattix.walkit.Gamma as WkGamma
import nl.mattix.walkit.LiveRun as WkLiveRun
import nl.mattix.walkit.Rect as WkRect
import nl.mattix.walkit.Region as WkRegion
import nl.mattix.walkit.Rgb as WkRgb
import nl.mattix.walkit.SkinOptions as WkSkinOptions
import nl.mattix.walkit.WindowId as WkWindowId
import nl.mattix.walkit.WindowPlan as WkWindowPlan

/**
 * The native engine's values come over into the engine module's types field for field: a frame's
 * commands with their pictures, tints and regions, a plan's runs, a menu both ways, the options
 * with their keys, window ids and edges. A run of text is placed by the engine and cannot be made
 * here; the engine's own test covers it.
 */
class WalkitTypesTest {
    private val part = WkBitmapDef("main.bg", "main.png", 1, 2, 10, 5, "group")

    @Test
    fun `a picture keeps its part, place, tint and scaling`() {
        val image = WalkitTypes.image(WkDrawCmd.Image(part, WkRect(3, 4, 10, 5), 200, WkGamma(1, 2, 3, 1, 2), smooth = true))

        assertEquals("main.png", image.bitmap.file)
        assertEquals(listOf(1, 2, 10, 5), listOf(image.bitmap.x, image.bitmap.y, image.bitmap.w, image.bitmap.h))
        assertEquals(Rect(3, 4, 10, 5), image.dst)
        assertEquals(200, image.alpha)
        assertEquals(listOf(1, 2, 3, 1, 2), image.gamma!!.let { listOf(it.r, it.g, it.b, it.boost, it.gray) })
        assertTrue(image.smooth)
    }

    @Test
    fun `each kind of command comes over as the same kind`() {
        val shape =
            WkRegion.Op(
                WkRegion.Box(WkRect(0, 0, 10, 5)),
                listOf(WkRegion.Pic(WkDrawCmd.Image(part, WkRect(0, 0, 10, 5), 255))),
                emptyList(),
                listOf(WkRegion.Box(WkRect(1, 1, 2, 2))),
            )
        val cmds =
            listOf(
                WkDrawCmd.WindowStart(shape),
                WkDrawCmd.Fill(WkRect(0, 0, 1, 1), WkRgb(0xff00ff), 200),
                WkDrawCmd.Clip(WkRect(0, 0, 2, 2)),
                WkDrawCmd.Pixels(WkRect(0, 0, 2, 1), 2, 1, intArrayOf(1, 2), 255, stable = true),
                WkDrawCmd.Unclip,
                WkDrawCmd.WindowEnd,
            ).map(WalkitTypes::cmd)

        val start = cmds[0] as DrawCmd.WindowStart
        val op = start.region as Region.Op
        assertEquals(Rect(0, 0, 10, 5), (op.base as Region.Box).rect)
        assertEquals(Rect(0, 0, 10, 5), (op.add.single() as Region.Pic).image.dst)
        assertEquals(Rect(1, 1, 2, 2), (op.cut.single() as Region.Box).rect)
        val fill = cmds[1] as DrawCmd.Fill
        assertEquals(0xff00ff, fill.color.value)
        assertEquals(200, fill.alpha)
        assertEquals(Rect(0, 0, 2, 2), (cmds[2] as DrawCmd.Clip).rect)
        val pixels = cmds[3] as DrawCmd.Pixels
        assertEquals(listOf(1, 2), pixels.argb.toList())
        assertTrue(pixels.stable)
        assertEquals(DrawCmd.Unclip, cmds[4])
        assertEquals(DrawCmd.WindowEnd, cmds[5])
    }

    @Test
    fun `a plan keeps its ids, versions and runs`() {
        val plan = WalkitTypes.plan(WkWindowPlan(7, 9, listOf(WkLiveRun(3, 4, 1, 5, WkRect(0, 0, 8, 8)))))

        assertEquals(7L, plan.id)
        assertEquals(9L, plan.version)
        val run = plan.runs.single()
        assertEquals(listOf(3L, 4L), listOf(run.id, run.version))
        assertEquals(1 until 5, run.from until run.to)
        assertEquals(Rect(0, 0, 8, 8), run.bounds)
    }

    @Test
    fun `a menu goes over and back as it was`() {
        val menu =
            SkinMenu(
                listOf(
                    SkinMenu.Command(1, "Open", checked = true, disabled = false),
                    SkinMenu.Separator,
                    SkinMenu.Submenu("More", SkinMenu(listOf(SkinMenu.Command(2, "Ticked", checked = false, disabled = true)))),
                ),
            )

        val back = WalkitTypes.menu(WalkitTypes.wk(menu))

        val open = back.items[0] as SkinMenu.Command
        assertEquals(listOf(1, "Open", true, false), listOf(open.id, open.text, open.checked, open.disabled))
        assertEquals(SkinMenu.Separator, back.items[1])
        val more = back.items[2] as SkinMenu.Submenu
        assertEquals("More", more.text)
        val ticked = more.menu.items.single() as SkinMenu.Command
        assertEquals(listOf(2, "Ticked", false, true), listOf(ticked.id, ticked.text, ticked.checked, ticked.disabled))
    }

    @Test
    fun `an option keeps its key, so a pick reaches the same attribute`() {
        val key = WkSkinOptions.Key("{1828D28F-78DD-4647-8532-EBA504B8FC04}", "animate")
        val entries =
            listOf(
                WkSkinOptions.Toggle(key, "Animate", checked = true, radio = true),
                WkSkinOptions.Separator,
                WkSkinOptions.Submenu("Drawers", listOf(WkSkinOptions.Toggle(key, "Top", checked = false))),
            ).map(WalkitTypes::entry)

        val toggle = entries[0] as SkinOptions.Toggle
        assertEquals(SkinOptions.Key(key.item, key.attribute), toggle.key)
        assertEquals(listOf("Animate", true, true), listOf(toggle.text, toggle.checked, toggle.radio))
        assertEquals(SkinOptions.Separator, entries[1])
        val sub = entries[2] as SkinOptions.Submenu
        assertEquals("Drawers", sub.text)
        assertEquals("Top", (sub.entries.single() as SkinOptions.Toggle).text)
    }

    @Test
    fun `window ids and edges go over by number`() {
        assertEquals(WindowId(3), WalkitTypes.id(WkWindowId(3)))
        assertEquals(WkWindowId(3), WalkitTypes.wk(WindowId(3)))
        assertEquals(Edge.START, WalkitTypes.edge(WkEdge.START))
        assertEquals(Edge.END, WalkitTypes.edge(WkEdge.END))
        assertNull(WalkitTypes.rect(WkRect(0, 0, 1, 1)).let { null })
    }
}
