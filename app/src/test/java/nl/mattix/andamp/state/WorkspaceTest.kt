// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Floating or in Double Size, upright or sideways: four workspaces, each with its own open
 * and collapsed windows, places, sizes and order. What is done in one is not found done in
 * another, and each is found again as it was left.
 */
class WorkspaceTest {
    private fun WinampState.enter(workspace: Workspace) {
        doubleSize = workspace.locked
        sideways = workspace.sideways
    }

    @Test
    fun `the workspace on screen follows Double Size and the way the phone is held`() {
        val s = WinampState()

        assertEquals(Workspace.FLOATING, s.workspace)
        s.sideways = true
        assertEquals(Workspace.FLOATING_SIDEWAYS, s.workspace)
        s.doubleSize = true
        assertEquals(Workspace.LOCKED_SIDEWAYS, s.workspace)
        s.sideways = false
        assertEquals(Workspace.LOCKED, s.workspace)
    }

    @Test
    fun `closing a window in one workspace leaves it open in the other three`() {
        val s = WinampState()
        s.enter(Workspace.LOCKED_SIDEWAYS)

        s.eqVisible = false

        Workspace.entries.filter { it != Workspace.LOCKED_SIDEWAYS }.forEach { other ->
            s.enter(other)
            assertEquals("$other", true, s.eqVisible)
        }
        s.enter(Workspace.LOCKED_SIDEWAYS)
        assertEquals(false, s.eqVisible)
    }

    @Test
    fun `the plug-in window's height in Double Size is one upright and another sideways`() {
        val s = WinampState()
        s.enter(Workspace.LOCKED)
        s.milkdropSteps = 4
        s.enter(Workspace.LOCKED_SIDEWAYS)
        s.milkdropSteps = 2

        s.enter(Workspace.LOCKED)
        assertEquals(4, s.milkdropSteps)
        s.enter(Workspace.FLOATING)
        assertNull("floating, it has no height of its own yet", s.milkdropSteps)
    }

    @Test
    fun `each workspace has its own window in front`() {
        val s = WinampState()
        s.enter(Workspace.FLOATING_SIDEWAYS)
        s.raiseWindow(WindowStore.PLAYLIST)

        s.enter(Workspace.LOCKED_SIDEWAYS)
        s.raiseWindow(WindowStore.EQ)

        assertEquals(WindowStore.EQ, s.windowOrder.last())
        s.enter(Workspace.FLOATING_SIDEWAYS)
        assertEquals(WindowStore.PLAYLIST, s.windowOrder.last())
        s.enter(Workspace.FLOATING)
        assertEquals(WindowStore.SKINS, s.windowOrder.last())
    }

    @Test
    fun `what is remembered holds every workspace`() {
        val s = WinampState()
        s.libraryOffset = IntOffset(10, 20)
        s.enter(Workspace.LOCKED)
        s.libraryOpen = true
        s.eqVisible = false

        val memory = s.placementOf(WindowStore.EQ)!!.asMemory()

        assertEquals(false, memory.of(Workspace.LOCKED).open)
        assertEquals(true, memory.of(Workspace.FLOATING).open)
        assertEquals(
            IntOffset(10, 20),
            s
                .placementOf(WindowStore.LIBRARY)!!
                .asMemory()
                .of(Workspace.FLOATING)
                .offset(),
        )
    }
}
