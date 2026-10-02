// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parts of Super Scope's frame that need no evaluator: the per-point draw
 * decision, the coordinate and color truncation, and the palette walk, each
 * checked against the numbers vis_avs e_superscope.cpp produces. The loop that
 * combines them runs preset code and is tested on a device.
 */
class SuperScopeMathsTest {
    @Test
    fun `skip below the epsilon draws, and negatives count as below`() {
        assertEquals(ScopeMark.DOT, scopeMark(drawMode = 0.0, skip = 0.0, isFirstPoint = false))
        assertEquals(ScopeMark.DOT, scopeMark(drawMode = 0.0, skip = -5.0, isFirstPoint = false))
        assertEquals(ScopeMark.NONE, scopeMark(drawMode = 0.0, skip = 1.0, isFirstPoint = false))
        assertEquals(ScopeMark.NONE, scopeMark(drawMode = 0.0, skip = 0.00001, isFirstPoint = false))
    }

    @Test
    fun `a NaN skip skips`() {
        assertEquals(ScopeMark.NONE, scopeMark(drawMode = 0.0, skip = Double.NaN, isFirstPoint = false))
    }

    @Test
    fun `any nonzero draw mode is lines`() {
        assertEquals(ScopeMark.LINE, scopeMark(drawMode = 1.0, skip = 0.0, isFirstPoint = false))
        assertEquals(ScopeMark.LINE, scopeMark(drawMode = 2.0, skip = 0.0, isFirstPoint = false))
        assertEquals(ScopeMark.DOT, scopeMark(drawMode = 0.0, skip = 0.0, isFirstPoint = false))
    }

    @Test
    fun `the first point of a lines scope draws nothing`() {
        assertEquals(ScopeMark.NONE, scopeMark(drawMode = 1.0, skip = 0.0, isFirstPoint = true))
        // dots have no memory: the first point is a dot like any other
        assertEquals(ScopeMark.DOT, scopeMark(drawMode = 0.0, skip = 0.0, isFirstPoint = true))
    }

    @Test
    fun `coordinates truncate and plus one lands just off the frame`() {
        assertEquals(0, scopeToPixel(-1.0, 100))
        assertEquals(50, scopeToPixel(0.0, 100))
        // (v + 1) * span * 0.5 = 2.6 must land on 2, not round to 3
        assertEquals(2, scopeToPixel(-0.948, 100))
        // x = 1 is pixel span itself, one past the last column, and is clipped
        assertEquals(100, scopeToPixel(1.0, 100))
        assertEquals(-1, scopeToPixel(Double.NaN, 100))
    }

    @Test
    fun `color channels truncate a half to 127`() {
        assertEquals(127, scopeChannel(0.5))
        assertEquals(0, scopeChannel(0.0))
        assertEquals(0, scopeChannel(-3.0))
        assertEquals(255, scopeChannel(1.0))
        assertEquals(255, scopeChannel(7.0))
        assertEquals(0, scopeChannel(Double.NaN))
    }

    @Test
    fun `the palette walk is one step along on its first frame`() {
        val walk = ScopeColourWalk(listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))

        // position 1 of 64: red channel (255 * 62 + 0 * 1) / 64 = 247,
        // blue channel (0 * 62 + 255 * 1) / 64 = 3
        assertEquals(0xFFF70003.toInt(), walk.next())
    }

    @Test
    fun `a single color renders a step under full brightness`() {
        val walk = ScopeColourWalk(listOf(0xFFFFFFFF.toInt()))

        // (255 * (63 - r) + 255 * r) / 64 = 251 whatever the position
        repeat(3) { assertEquals(0xFFFBFBFB.toInt(), walk.next()) }
    }

    @Test
    fun `the last color blends back toward the first`() {
        val walk = ScopeColourWalk(listOf(0xFF000000.toInt(), 0xFF0000FF.toInt()))

        // position 96 is halfway from the second color back to the wrapped-around
        // first: blue channel (255 * (63 - 32) + 0 * 32) / 64 = 123
        var last = 0
        repeat(96) { last = walk.next() }

        assertEquals(0xFF00007B.toInt(), last)
    }
}
