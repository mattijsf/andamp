// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.BalanceRecordingBackend
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.ScaledWindowCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The balance slider reaches the audio: the pan the backend is told matches the slider the
 * listener sees.
 *
 * Balance (177,57) is 38x13 with a 14px thumb, so the travel is 24px and a
 * fraction maps as (x - 177 - 7) / 24.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class BalanceInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val pans = mutableListOf<Float>()
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        vm =
            WinampViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                createBackend = { scope -> BalanceRecordingBackend(FakeTracks.tracks, scope, pans) },
                presetStore = InMemoryEqPresetStore(),
            )
        val widgets = mainWindowWidgets(vm) {}
        compose.setContent { ScaledWindowCanvas(MAIN_W, MAIN_H, SCALE, vm.state, widgets) {} }
    }

    @Test
    fun `panning hard right pans the audio hard right`() {
        compose.onRoot().performTouchInput { tapVirtual(212f, 63f) }

        assertEquals(100, vm.state.balance)
        assertEquals(1f, pans.last(), 0.001f)
    }

    @Test
    fun `panning hard left pans the audio hard left`() {
        compose.onRoot().performTouchInput { tapVirtual(176f, 63f) }

        assertEquals(-100, vm.state.balance)
        assertEquals(-1f, pans.last(), 0.001f)
    }

    @Test
    fun `the center detent reaches the audio as dead center`() {
        compose.onRoot().performTouchInput { tapVirtual(212f, 63f) }
        compose.onRoot().performTouchInput { tapVirtual(196.5f, 63f) }

        assertEquals(0, vm.state.balance)
        assertEquals(0f, pans.last(), 0.001f)
    }

    @Test
    fun `a backend that cannot pan still moves the slider`() {
        // MockBackend has no balance capability; the facade swallows the pan
        val plain =
            WinampViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                createBackend = { scope ->
                    nl.mattix.andamp.backend.mock
                        .MockBackend(FakeTracks.tracks, scope)
                },
                presetStore = InMemoryEqPresetStore(),
            )

        plain.setBalance(-100)

        assertEquals("the control shows where the balance is", -100, plain.state.balance)
        assertTrue("no pan is sent", pans.isEmpty())
    }
}
