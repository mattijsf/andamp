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

/** The locked stack's own collapsed windows outlive a launch, apart from the floating layout's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StackShadeMemoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a window collapsed in the locked stack is stored with Double Size`() {
        val vm = testViewModel()
        settle()
        vm.doubleSize.on = true

        vm.state.setShaded(WindowStore.EQ, true, EQ_H)
        settle()

        assertEquals(setOf(WindowStore.EQ), vm.doubleSize.shaded)
        assertEquals(
            "not with the floating layout",
            false,
            vm.state
                .placementOf(WindowStore.EQ)
                ?.asMemory()
                ?.shaded ?: false,
        )
    }

    @Test
    fun `the next launch finds it collapsed in the stack and open while floating`() {
        DoubleSizeStore(app).shaded = setOf(WindowStore.MAIN)

        val vm = testViewModel()
        settle()

        assertEquals(setOf(WindowStore.MAIN), vm.state.stackShaded)
        vm.doubleSize.on = false
        assertEquals("floating, the player stands open", false, vm.state.mainShaded)
        vm.doubleSize.on = true
        assertTrue(vm.state.mainShaded)
    }
}
