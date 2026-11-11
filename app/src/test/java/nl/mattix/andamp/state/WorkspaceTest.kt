// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Upright or sideways: two workspaces, each with its own open and collapsed windows, places,
 * sizes and order. What is done in one is not found done in the other, and each is found
 * again as it was left.
 */
class WorkspaceTest {
    @Test
    fun `the workspace on screen follows the way the phone is held`() {
        val s = WinampState()

        assertEquals(Workspace.FLOATING, s.workspace)
        s.sideways = true
        assertEquals(Workspace.FLOATING_SIDEWAYS, s.workspace)
    }

    @Test
    fun `closing a window in one workspace leaves it open in the other`() {
        val s = WinampState()
        s.sideways = true

        s.eqVisible = false

        s.sideways = false
        assertEquals(true, s.eqVisible)
        s.sideways = true
        assertEquals(false, s.eqVisible)
    }

    @Test
    fun `each workspace has its own window in front`() {
        val s = WinampState()
        s.sideways = true
        s.raiseWindow(WindowStore.PLAYLIST)

        assertEquals(WindowStore.PLAYLIST, s.windowOrder.last())
        s.sideways = false
        assertEquals(WindowStore.SKINS, s.windowOrder.last())
    }

    @Test
    fun `what is remembered holds every workspace`() {
        val s = WinampState()
        s.libraryOffset = IntOffset(10, 20)
        s.sideways = true
        s.eqVisible = false

        val memory = s.placementOf(WindowStore.EQ).asMemory()

        assertEquals(false, memory.of(Workspace.FLOATING_SIDEWAYS).open)
        assertEquals(true, memory.of(Workspace.FLOATING).open)
        assertEquals(
            IntOffset(10, 20),
            s
                .placementOf(WindowStore.LIBRARY)
                .asMemory()
                .of(Workspace.FLOATING)
                .offset(),
        )
    }
}
