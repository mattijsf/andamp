// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FakeBeat], the drum pattern behind a player with nothing playing: kick, snare and hat
 * land at different points of the bar.
 */
class FakeBeatTest {
    private val beat = FakeBeat.BAR / 4

    @Test
    fun `the kick is on every beat`() {
        for (at in 0..3) {
            assertTrue("beat $at", FakeBeat.kick(at * beat) > LANDED)
        }
    }

    @Test
    fun `the snare is on two and four, and nowhere else`() {
        assertTrue(FakeBeat.snare(beat) > LANDED)
        assertTrue(FakeBeat.snare(3 * beat) > LANDED)
        assertTrue("on the one", FakeBeat.snare(0.0) < SILENT)
        assertTrue("on the three", FakeBeat.snare(2 * beat) < SILENT)
    }

    @Test
    fun `the hat is between the beats`() {
        assertTrue(FakeBeat.hat(beat / 2) > LANDED)
        assertTrue("on the beat", FakeBeat.hat(0.0) < SILENT)
    }

    @Test
    fun `a hit is over almost as soon as it lands`() {
        // the envelope is short; the fall the listener sees is the display's gravity
        assertTrue(FakeBeat.kick(0.0) > LANDED)
        assertTrue("the kick is silent after 120 ms", FakeBeat.kick(0.12) < SILENT)
    }

    @Test
    fun `the pattern keeps going, bar after bar`() {
        assertTrue(FakeBeat.kick(FakeBeat.BAR) > LANDED)
        assertTrue(FakeBeat.kick(10 * FakeBeat.BAR) > LANDED)
        assertTrue(FakeBeat.snare(10 * FakeBeat.BAR + beat) > LANDED)
    }

    @Test
    fun `the same moment is always the same hit`() {
        assertEquals(FakeBeat.kick(3.317), FakeBeat.kick(3.317), 0.0)
        assertEquals(FakeBeat.hat(9.1), FakeBeat.hat(9.1), 0.0)
    }

    @Test
    fun `no hit is ever harder than full`() {
        var t = 0.0
        repeat(600) {
            assertTrue(FakeBeat.kick(t) in 0.0..1.0)
            assertTrue(FakeBeat.snare(t) in 0.0..1.0)
            assertTrue(FakeBeat.hat(t) in 0.0..1.0)
            t += 1.0 / 60
        }
    }

    private companion object {
        /** Below full, because a hit's velocity varies by bar. */
        const val LANDED = 0.8

        const val SILENT = 0.1
    }
}
