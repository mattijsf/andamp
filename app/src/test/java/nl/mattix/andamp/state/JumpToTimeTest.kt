// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What [JumpToTime.seconds] reads from the one line a listener types, and what it refuses. */
class JumpToTimeTest {
    @Test
    fun `minutes and seconds`() {
        assertEquals(90, JumpToTime.seconds("1:30"))
        assertEquals(0, JumpToTime.seconds("0:00"))
        assertEquals(3_600, JumpToTime.seconds("60:00"))
    }

    @Test
    fun `a dot reads as a colon`() {
        assertEquals(90, JumpToTime.seconds("1.30"))
    }

    @Test
    fun `a bare number is seconds`() {
        assertEquals(90, JumpToTime.seconds("90"))
        assertEquals(7, JumpToTime.seconds("7"))
    }

    @Test
    fun `spaces are ignored`() {
        assertEquals(90, JumpToTime.seconds(" 1 : 30 "))
    }

    @Test
    fun `seconds past a minute are taken as typed`() {
        // "1:90" is a minute and ninety seconds
        assertEquals(150, JumpToTime.seconds("1:90"))
    }

    @Test
    fun `what is not a time is refused`() {
        assertNull(JumpToTime.seconds(""))
        assertNull(JumpToTime.seconds("   "))
        assertNull(JumpToTime.seconds("half past two"))
        assertNull(JumpToTime.seconds("1:2:3"))
        assertNull(JumpToTime.seconds("1:"))
        assertNull(JumpToTime.seconds("-5"))
        assertNull(JumpToTime.seconds("1:-30"))
    }
}
