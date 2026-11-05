// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skinplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/** A written value reads as written until the state shows it, and a change made elsewhere reads at once. */
class HeldWriteTest {
    private var now = 0L
    private val held = HeldWrite(slack = 2, clock = { now })

    private fun read(mirror: Int) = held.read(mirror) { it * 10 }

    @Test
    fun `a write reads as written while the state still shows the old value, until the hold runs out`() {
        held.write(value = 500, image = 50, mirror = 20)
        assertEquals(500, read(20))
        now = HeldWrite.HOLD_MS
        assertEquals("the hold ran out", 200, read(20))
    }

    @Test
    fun `a write that lands near its image holds on the value it landed on`() {
        held.write(value = 500, image = 50, mirror = 20)
        assertEquals("landed within the slack", 500, read(49))
        assertEquals("only the landed value holds it", 500, read(49))
        assertEquals("a change made elsewhere reads at once", 300, read(30))
    }

    @Test
    fun `a value from elsewhere ends the hold at once`() {
        held.write(value = 500, image = 50, mirror = 20)
        assertEquals(800, read(80))
        assertEquals(200, read(20))
    }

    @Test
    fun `with nothing written the state reads through`() {
        assertEquals(70, read(7))
    }
}
