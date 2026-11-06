// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.walkit.LiveRun
import nl.mattix.walkit.Rect
import nl.mattix.walkit.WindowPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a host records again of a modern skin's frame, and what it keeps. */
class KeptFramesTest {
    private var made = 0
    private val released = mutableListOf<String>()
    private val kept = KeptFrames(create = { "d${made++}" }, release = { released += it })

    private val main = Rect(0, 0, 275, 116)
    private val list = Rect(0, 116, 275, 200)

    private fun run(
        id: Long,
        version: Long,
        from: Int = 0,
        to: Int = 1,
    ) = LiveRun(id, version, from, to, Rect(0, 0, 10, 10))

    private fun plan(
        id: Long,
        version: Long,
        vararg runs: LiveRun,
    ) = WindowPlan(id, version, runs.toList())

    @Test
    fun `the first frame records everything, and the same frame again records nothing`() {
        val first = kept.plan(listOf(plan(1, 1, run(10, 1)), plan(2, 1)), listOf(main, list))
        assertTrue(first.all { it.again })
        assertTrue(first[0].runs.single().again)
        assertTrue(kept.stackAgain(2, 550, 632))

        val second = kept.plan(listOf(plan(1, 1, run(10, 1)), plan(2, 1)), listOf(main, list))
        assertFalse(second.any { it.again })
        assertFalse(second[0].runs.single().again)
        assertSame("the same drawing", first[0].drawing, second[0].drawing)
        assertFalse(kept.stackAgain(2, 550, 632))
    }

    @Test
    fun `a run that changed is recorded again and leaves its window as it was`() {
        kept.plan(listOf(plan(1, 1, run(10, 1))), listOf(main))
        val next = kept.plan(listOf(plan(1, 1, run(10, 2))), listOf(main)).single()
        assertFalse(next.again)
        assertTrue(next.runs.single().again)
    }

    @Test
    fun `a window is recorded again when its version, its size, its runs or its place at the bottom change`() {
        kept.plan(listOf(plan(1, 1, run(10, 1))), listOf(main))
        assertTrue("version", kept.plan(listOf(plan(1, 2, run(10, 1))), listOf(main)).single().again)
        assertTrue("size", kept.plan(listOf(plan(1, 2, run(10, 1))), listOf(main.copy(width = 300))).single().again)
        assertFalse("moved only", kept.plan(listOf(plan(1, 2, run(10, 1))), listOf(Rect(5, 5, 300, 116))).single().again)
        assertTrue(
            "a run came",
            kept.plan(listOf(plan(1, 2, run(10, 1), run(11, 1, 1, 2))), listOf(Rect(5, 5, 300, 116))).single().again,
        )
        assertTrue("a run went", kept.plan(listOf(plan(1, 2, run(10, 1))), listOf(Rect(5, 5, 300, 116))).single().again)
        assertEquals(1, kept.keptRuns)

        val moved = kept.plan(listOf(plan(2, 1), plan(1, 2, run(10, 1))), listOf(list, Rect(5, 5, 300, 116)))
        assertTrue("no longer at the bottom", moved[1].again)
        assertFalse(moved[1].overClear)
        assertTrue(moved[0].overClear)
    }

    @Test
    fun `windows and runs a frame no longer shows are let go of`() {
        kept.plan(listOf(plan(1, 1, run(10, 1)), plan(2, 1)), listOf(main, list))
        kept.plan(listOf(plan(1, 1)), listOf(main))
        assertEquals(listOf("d1", "d2"), released.sorted())
        assertEquals(1, kept.keptWindows)
        assertEquals(0, kept.keptRuns)
    }

    @Test
    fun `the stack layer is recorded again when its windows or its size change`() {
        val a = kept.plan(listOf(plan(1, 1)), listOf(main))
        assertTrue(kept.stackAgain(2, 550, 232))
        kept.plan(listOf(plan(1, 1)), listOf(main))
        assertTrue("size", kept.stackAgain(2, 550, 400))
        assertTrue("scale", kept.stackAgain(3, 550, 400))
        kept.plan(listOf(plan(1, 1), plan(2, 1)), listOf(main, list))
        assertTrue("a window opened", kept.stackAgain(3, 550, 400))

        kept.forget()
        val b = kept.plan(listOf(plan(1, 1)), listOf(main))
        assertNotSame("forgotten: a new drawing", a.single().drawing, b.single().drawing)
        assertTrue(b.single().again)
        assertTrue(kept.stackAgain(3, 550, 400))
    }

    @Test
    fun `a frame fits when each window has a plan, a size and its runs in order, at a size a host can hold`() {
        val plans = listOf(plan(1, 1, run(10, 1, 0, 2), run(11, 1, 2, 3)))
        assertTrue(KeptFrames.fits(listOf(main), plans, listOf(3), whole = 2))
        assertFalse("a run past the commands", KeptFrames.fits(listOf(main), plans, listOf(2), whole = 2))
        assertFalse(
            "runs out of order",
            KeptFrames.fits(listOf(main), listOf(plan(1, 1, run(10, 1, 2, 3), run(11, 1, 0, 2))), listOf(3), 2),
        )
        assertFalse("no plan", KeptFrames.fits(listOf(main), emptyList(), listOf(3), 2))
        assertFalse("no height", KeptFrames.fits(listOf(main.copy(height = 0)), plans, listOf(3), 2))
        assertFalse("too large", KeptFrames.fits(listOf(main), plans, listOf(3), whole = 20))
    }
}
