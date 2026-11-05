// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skinplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Winamp 5's scales against the state's: the ends and the flat land on each other, and a state value comes back. */
class WinampFiveUnitsTest {
    @Test
    fun `the volume holds at the ends and every percent comes back`() {
        assertEquals(0, WinampFiveUnits.fromPercent(0))
        assertEquals(255, WinampFiveUnits.fromPercent(100))
        assertEquals(0, WinampFiveUnits.toPercent(0))
        assertEquals(100, WinampFiveUnits.toPercent(255))
        for (p in 0..100) assertEquals("percent $p", p, WinampFiveUnits.toPercent(WinampFiveUnits.fromPercent(p)))
    }

    @Test
    fun `the equalizer holds at the ends and the flat, and every band comes back`() {
        assertEquals(0, WinampFiveUnits.fromEq(0))
        assertEquals(128, WinampFiveUnits.fromEq(32))
        assertEquals(255, WinampFiveUnits.fromEq(63))
        assertEquals(0, WinampFiveUnits.toEq(0))
        assertEquals(32, WinampFiveUnits.toEq(128))
        assertEquals(63, WinampFiveUnits.toEq(255))
        for (v in 0..63) assertEquals("band $v", v, WinampFiveUnits.toEq(WinampFiveUnits.fromEq(v)))
    }

    @Test
    fun `the balance holds at the ends and every value comes back`() {
        assertEquals(-127, WinampFiveUnits.fromBalance(-100))
        assertEquals(100, WinampFiveUnits.toBalance(127))
        for (b in -100..100) assertEquals("balance $b", b, WinampFiveUnits.toBalance(WinampFiveUnits.fromBalance(b)))
    }
}
