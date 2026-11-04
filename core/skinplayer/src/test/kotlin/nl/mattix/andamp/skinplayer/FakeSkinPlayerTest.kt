// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.skinplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fake player keeps the contract, and does nothing for a capability it lacks. */
class FakeSkinPlayerTest : SkinPlayerContract() {
    private var now = 0L

    override fun player(playlist: List<Entry>): SkinPlayer = FakeSkinPlayer(playlist, clock = { now })

    @Test
    fun `the position runs while it plays and holds while it pauses`() {
        val p = FakeSkinPlayer(listOf(Entry("a", 60_000)), clock = { now })
        p.play()
        now += 1_500
        assertEquals(1_500L, p.positionMs())
        p.pause()
        now += 1_000
        assertEquals(1_500L, p.positionMs())
    }

    @Test
    fun `without an equalizer its commands do nothing`() {
        val p = FakeSkinPlayer(capabilities = Capabilities(eq = false))
        val before = p.state
        p.setEqBand(0, 60)
        p.setPreamp(1)
        p.setEqEnabled(false)
        assertEquals(before, p.state)
    }

    @Test
    fun `Eject asks the host for a file`() {
        val p = FakeSkinPlayer()
        p.requestOpen(andPlay = true)
        assertEquals(listOf(true), p.openRequests)
    }
}
