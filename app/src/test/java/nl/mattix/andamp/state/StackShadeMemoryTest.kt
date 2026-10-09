// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.setShaded
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The locked workspace's own collapsed windows outlive a launch, apart from the floating one's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StackShadeMemoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a window collapsed in the locked workspace is stored with it, not with the floating one`() {
        val vm = testViewModel()
        settle()
        vm.doubleSize.on = true

        vm.state.setShaded(WindowStore.EQ, true, EQ_H)
        settle()

        val stored = vm.state.placementOf(WindowStore.EQ)!!.asMemory()
        assertEquals(true, stored.of(Workspace.LOCKED).shaded)
        assertEquals("not with the floating layout", false, stored.of(Workspace.FLOATING).shaded)
    }

    @Test
    fun `the next launch finds it collapsed in the locked workspace and open while floating`() {
        WindowStore(app).save(
            WindowLayoutMemory(
                placements =
                    mapOf(WindowStore.MAIN to WindowPlacement(mapOf(Workspace.LOCKED to WindowArrangement(shaded = true)))),
            ),
        )

        val vm = testViewModel()
        settle()

        vm.doubleSize.on = false
        assertEquals("floating, the player stands open", false, vm.state.mainShaded)
        vm.doubleSize.on = true
        assertTrue(vm.state.mainShaded)
    }

    @Test
    fun `what Double Size kept of its own before is read into the locked workspace`() {
        // Double Size's settings as a build before the workspaces wrote them, and no window
        // layout saved since
        app
            .getSharedPreferences("doublesize", 0)
            .edit()
            .putStringSet("shaded", setOf(WindowStore.MAIN))
            .putInt("vis_steps", 7)
            .commit()

        val vm = testViewModel()
        settle()

        vm.doubleSize.on = true
        assertTrue(vm.state.mainShaded)
        assertEquals(7, vm.state.milkdropSteps)
        vm.doubleSize.on = false
        assertEquals(false, vm.state.mainShaded)
    }
}
