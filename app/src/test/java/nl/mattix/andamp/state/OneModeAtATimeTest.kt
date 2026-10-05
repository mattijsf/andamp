// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.overlay.OverlayStore
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Always On Top and Double Size are on one at a time: a player that floats over other apps
 * and a player that fills the screen are two answers to the same question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OneModeAtATimeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A view model that may draw over other apps, with Double Size on as on a fresh install. */
    private fun viewModel(): WinampViewModel {
        ShadowSettings.setCanDrawOverlays(true)
        return testViewModel().also { assertTrue(it.doubleSize.on) }
    }

    @Test
    fun `switching always on top on switches double size off`() {
        val vm = viewModel()

        vm.overlayOps.want(true)

        assertFalse(vm.doubleSize.on)
        assertFalse("the D goes dark", vm.state.doubleSize)
        assertTrue("the A is lit", vm.state.alwaysOnTop)
    }

    @Test
    fun `switching double size on switches always on top off`() {
        val vm = viewModel()
        vm.overlayOps.want(true)

        vm.doubleSize.toggle()

        assertTrue(vm.doubleSize.on)
        assertFalse(vm.overlayOps.gate.wanted)
        assertFalse("the A goes dark", vm.state.alwaysOnTop)
        assertFalse("and it is stored that way", OverlayStore(app).wanted)
    }

    @Test
    fun `switching either off leaves the other off`() {
        val vm = viewModel()

        vm.doubleSize.toggle()
        assertFalse(vm.overlayOps.gate.wanted)

        vm.overlayOps.want(true)
        vm.overlayOps.want(false)
        assertFalse(vm.doubleSize.on)
    }

    @Test
    fun `always on top that is refused for want of the permission leaves double size on`() {
        ShadowSettings.setCanDrawOverlays(false)
        val vm = testViewModel()
        var asked = 0

        vm.overlayOps.askFor(true, prompt = { asked++ }, openSettings = {})

        assertEquals("the listener was asked for the permission", 1, asked)
        assertFalse(vm.overlayOps.gate.wanted)
        assertTrue(vm.doubleSize.on)
    }

    @Test
    fun `an install that was left with both on starts floating, with double size off`() {
        OverlayStore(app).wanted = true
        DoubleSizeStore(app) { true }.on = true

        val vm = testViewModel()

        assertTrue(vm.overlayOps.gate.wanted)
        assertFalse(vm.doubleSize.on)
        assertFalse(vm.state.doubleSize)
    }
}
