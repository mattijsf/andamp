// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Test

/** A modern skin's balance and preamp as the app's player takes them: the balance's scale, and the snaps. */
class WalPlayerBalanceTest {
    private val state = WinampState()
    private val controls = DeferredControls(state)
    private val posted = ArrayDeque<Runnable>()

    private val player =
        WalPlayer(
            controls = controls,
            state = state,
            tapOf = { null },
            positionMs = { controls.reportedMs },
            openFile = {},
            post = { posted += it },
            clock = { controls.now },
        )

    /** Runs what the player posted to main, as the main thread would. */
    private fun drain() {
        while (posted.isNotEmpty()) posted.removeFirst().run()
    }

    @Test
    fun `the balance reaches the player on its scale, and reads as the player has it`() {
        assertEquals("the middle", 0, player.pan)

        player.pan = -127
        player.pan = 64
        assertEquals("held as written", 64, player.pan)
        drain()
        controls.apply()

        assertEquals(listOf("setBalance(50)"), controls.calls)
        assertEquals("landed", 64, player.pan)
        state.balance = -100
        assertEquals(-127, player.pan)
        assertEquals(100, WalPlayer.toBalance(127))
        for (b in -100..100) assertEquals("balance $b", b, WalPlayer.toBalance(WalPlayer.fromBalance(b)))
    }

    @Test
    fun `a balance near the middle and a preamp near 0 dB snap there, as the classic window's do`() {
        player.pan = 20
        player.eqPreamp = 133
        assertEquals(0, player.pan)
        assertEquals(128, player.eqPreamp)
        drain()

        assertEquals(listOf("setPreamp(32)", "setBalance(0)"), controls.calls)
        player.pan = 30
        assertEquals("past the snap", 30, player.pan)
    }
}
