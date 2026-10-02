// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A window that goes from off to on lands in front, where no other window covers it. Closing
 * leaves the stack as it was.
 */
class WindowOpensInFrontTest {
    private fun state() =
        WinampState().apply {
            eqVisible = false
            plVisible = false
            milkdropOn = false
            libraryOpen = false
            skinManagerOpen = false
            raiseWindow(WindowStore.MAIN)
        }

    /** Every window a menu switches on, and the flag it switches. */
    private fun switches(state: WinampState) =
        mapOf(
            WindowStore.EQ to { on: Boolean -> state.eqVisible = on },
            WindowStore.PLAYLIST to { on: Boolean -> state.plVisible = on },
            WindowStore.MILKDROP to { on: Boolean -> state.milkdropOn = on },
            WindowStore.LIBRARY to { on: Boolean -> state.libraryOpen = on },
            WindowStore.SKINS to { on: Boolean -> state.skinManagerOpen = on },
        )

    @Test
    fun `every window that opens ends up on top`() {
        switches(state()).keys.forEach { id ->
            val state = state()

            state.setWindowOpen(id, true, switches(state).getValue(id))

            assertEquals("$id opens on top", id, state.windowOrder.last())
        }
    }

    @Test
    fun `closing leaves the stack as it was`() {
        val state = state()
        state.setWindowOpen(WindowStore.LIBRARY, true) { state.libraryOpen = it }
        state.setWindowOpen(WindowStore.SKINS, true) { state.skinManagerOpen = it }
        val order = state.windowOrder

        state.setWindowOpen(WindowStore.LIBRARY, false) { state.libraryOpen = it }

        assertFalse(state.libraryOpen)
        assertEquals("closing keeps the stack order", order, state.windowOrder)
    }

    @Test
    fun `opening the one already in front changes nothing`() {
        val state = state()
        state.setWindowOpen(WindowStore.SKINS, true) { state.skinManagerOpen = it }
        val order = state.windowOrder

        state.setWindowOpen(WindowStore.SKINS, true) { state.skinManagerOpen = it }

        assertEquals(order, state.windowOrder)
    }
}
