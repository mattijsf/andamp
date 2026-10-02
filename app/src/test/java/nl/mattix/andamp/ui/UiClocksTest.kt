// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.Flung
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The clocks that only serve the eye stop when the app leaves the screen.
 *
 * Ticking for as long as the app exists, each would be a state write that costs a
 * recomposition nobody sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiClocksTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner = TestOwner()

    private fun start(state: WinampState) {
        compose.mainClock.autoAdvance = false // the clocks never idle: drive time by hand
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { UiClocks(state) }
        }
        compose.mainClock.advanceTimeBy(1)
    }

    @Test
    fun `the marquee steps while the app is on screen`() {
        val state = WinampState()
        start(state)

        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("the marquee steps while on screen: ${state.marqueeStep}", state.marqueeStep >= 4)
    }

    @Test
    fun `the marquee stops when the app is stopped, and picks up again when it returns`() {
        val state = WinampState()
        start(state)
        compose.mainClock.advanceTimeBy(1_000)

        owner.registry.currentState = Lifecycle.State.CREATED
        val whenStopped = state.marqueeStep
        compose.mainClock.advanceTimeBy(10_000)

        assertEquals("the marquee holds still in the background", whenStopped, state.marqueeStep)

        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("the marquee steps again on return", state.marqueeStep > whenStopped)
    }

    @Test
    fun `the pause blink stops when the app is stopped`() {
        val state = WinampState()
        state.transport = Transport.Paused
        start(state)
        compose.mainClock.advanceTimeBy(2_500)

        owner.registry.currentState = Lifecycle.State.CREATED
        compose.mainClock.advanceTimeBy(10_000)

        // stopped mid-blink would come back to a hole where the time should be
        assertTrue("the time is visible when the app stops", state.blinkOn)
    }

    @Test
    fun `the blink runs while the app is on screen and paused`() {
        val state = WinampState()
        state.transport = Transport.Paused
        start(state)

        compose.mainClock.advanceTimeBy(500)
        assertEquals("the time blinks out after half a second", false, state.blinkOn)

        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("the time is back a second later", true, state.blinkOn)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `a finger on the title stops the clock, and it waits a moment after`() {
        // webamp's Marquee stops stepping while the title is dragged and
        // resumes a second after the drag ceases, so a nudge is not undone
        // before it has been read
        val state = WinampState()
        start(state)

        compose.mainClock.advanceTimeBy(MARQUEE_STEP_MS * 3)
        val stepped = state.marqueeStep
        assertTrue("the title steps on its own", stepped > 0)

        state.marqueeHeld = true
        compose.mainClock.advanceTimeBy(MARQUEE_STEP_MS * 5)
        assertEquals("the clock holds under the finger", stepped, state.marqueeStep)

        state.marqueeHeld = false
        compose.mainClock.advanceTimeBy(MARQUEE_STEP_MS * 2)
        assertEquals("the clock waits out the pause after the finger lifts", stepped, state.marqueeStep)

        compose.mainClock.advanceTimeBy(1_000L + MARQUEE_STEP_MS * 2)
        assertTrue("the clock steps again after the pause", state.marqueeStep > stepped)
    }

    @Test
    fun `a thrown list travels, rather than arriving at once`() {
        // guards against a throw timed with the wall clock and carried with the
        // frame clock, which arrives in its first frame
        val state = WinampState()
        val visited = mutableListOf<Int>()
        start(state)
        state.flung = Flung(rowsPerSecond = 30f, from = 100) { visited += it }

        compose.mainClock.advanceTimeBy(16)
        val first = visited.lastOrNull()
        compose.mainClock.advanceTimeBy(300)

        assertTrue("the throw carries the list", first != null)
        assertTrue("the list travels forward: $first", first!! >= 100)
        assertTrue("the first frame moves fewer than five rows: $first", first < 105)
        assertTrue("the list travels past its start: ${visited.last()}", visited.last() > 100)
    }

    @Test
    fun `a throw comes to rest and lets go of the list`() {
        val state = WinampState()
        start(state)
        state.flung = Flung(rowsPerSecond = 30f, from = 0) {}

        compose.mainClock.advanceTimeBy(3_000)

        assertEquals("the throw lets go of the list", null, state.flung)
    }
}
