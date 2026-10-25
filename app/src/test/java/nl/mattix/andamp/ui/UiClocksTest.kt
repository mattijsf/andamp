// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.Flung
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    private fun start(
        state: WinampState,
        followsScreen: Boolean = false,
    ) {
        compose.mainClock.autoAdvance = false // the clocks never idle: drive time by hand
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { UiClocks(state, followsScreen) }
        }
        compose.mainClock.advanceTimeBy(1)
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val power = shadowOf(app.getSystemService(PowerManager::class.java))

    /** The phone's screen goes dark or lights, as the system tells the app. */
    private fun screen(on: Boolean) {
        power.setIsInteractive(on)
        app.sendBroadcast(Intent(if (on) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the marquee rests while the phone's screen is dark, and steps again when it lights`() {
        val state = WinampState()
        start(state, followsScreen = true)
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("it steps with the screen on", state.marqueeStep > 0)

        screen(on = false)
        compose.mainClock.advanceTimeBy(MARQUEE_STEP_MS)
        val dark = state.marqueeStep
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals("it holds still in the dark, as the floating player stays started", dark, state.marqueeStep)

        screen(on = true)
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("it steps again once the screen lights", state.marqueeStep > dark)
    }

    @Test
    fun `a surface on another display keeps the marquee stepping while the phone's screen is dark`() {
        val state = WinampState()
        start(state)
        screen(on = false)
        val dark = state.marqueeStep

        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("a car's screen shows it on", state.marqueeStep > dark)
    }

    @Test
    fun `a dark screen hands the clocks to a surface on another display`() {
        val state = WinampState()
        val car = TestOwner()
        compose.mainClock.autoAdvance = false
        owner.registry.currentState = Lifecycle.State.RESUMED
        car.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { UiClocks(state, followsScreen = true) }
            CompositionLocalProvider(LocalLifecycleOwner provides car) { UiClocks(state) }
        }
        compose.mainClock.advanceTimeBy(1_000)

        screen(on = false)
        val dark = state.marqueeStep
        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("the car's surface steps it", state.marqueeStep > dark)
    }

    @Test
    fun `the clocks rest while nothing they drive shows, and step again once something does`() {
        val state = WinampState()
        var shown by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { UiClocks(state, shown = { shown }) }
        }
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals("nothing shows the title: it holds still", 0, state.marqueeStep)

        shown = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("it steps once something shows it", state.marqueeStep > 0)
    }

    @Test
    fun `a surface that shows nothing the clocks drive hands them to one that does`() {
        val state = WinampState()
        val car = TestOwner()
        var shown by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        owner.registry.currentState = Lifecycle.State.RESUMED
        car.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { UiClocks(state, shown = { shown }) }
            CompositionLocalProvider(LocalLifecycleOwner provides car) { UiClocks(state) }
        }
        compose.mainClock.advanceTimeBy(1_000)

        shown = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(MARQUEE_STEP_MS)
        val handed = state.marqueeStep
        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("the car's surface steps it", state.marqueeStep > handed)
    }

    @Test
    fun `no frame is asked for while no list is thrown`() =
        runTest {
            val clock = CountingFrameClock(testScheduler)
            val state = WinampState()
            val clocks = launch(clock) { coroutineScope { stepClocks(state) } }

            advanceTimeBy(2_000)
            assertEquals("the marquee and the blink ask for no display frame", 0, clock.asked)
            assertTrue("while the marquee steps", state.marqueeStep > 0)

            state.flung = Flung(rowsPerSecond = 30f, from = 0) {}
            Snapshot.sendApplyNotifications()
            advanceTimeBy(100)
            assertTrue("a throw asks for frames", clock.asked > 0)

            advanceTimeBy(5_000)
            Snapshot.sendApplyNotifications()
            advanceTimeBy(100)
            assertEquals("the throw came to rest", null, state.flung)
            val rested = clock.asked
            advanceTimeBy(2_000)
            assertEquals("and the frames stop with it", rested, clock.asked)
            clocks.cancel()
        }

    /** A display that draws a frame every 16 ms, counting the frames asked of it. */
    private class CountingFrameClock(
        private val scheduler: TestCoroutineScheduler,
    ) : MonotonicFrameClock {
        var asked = 0

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            asked++
            delay(16)
            return onFrame(scheduler.currentTime * 1_000_000)
        }
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

    /** The player on the phone's screen and on a car's at once: two surfaces over one state. */
    @Test
    fun `two surfaces step the marquee no faster than one`() {
        val alone = WinampState()
        val shared = WinampState()
        compose.mainClock.autoAdvance = false
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UiClocks(alone)
                UiClocks(shared)
                UiClocks(shared)
            }
        }

        compose.mainClock.advanceTimeBy(2_000)

        assertTrue("the marquee steps: ${alone.marqueeStep}", alone.marqueeStep > 0)
        assertEquals(alone.marqueeStep, shared.marqueeStep)
    }

    @Test
    fun `when the surface that steps the clocks stops, the other takes over`() {
        val state = WinampState()
        val second = TestOwner()
        compose.mainClock.autoAdvance = false
        owner.registry.currentState = Lifecycle.State.RESUMED
        second.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { UiClocks(state) }
            CompositionLocalProvider(LocalLifecycleOwner provides second) { UiClocks(state) }
        }
        compose.mainClock.advanceTimeBy(1_000)

        owner.registry.currentState = Lifecycle.State.CREATED
        val whenFirstStopped = state.marqueeStep
        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("the marquee goes on stepping", state.marqueeStep > whenFirstStopped)
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
        // seen at once, as the app's snapshot manager makes a write seen
        Snapshot.sendApplyNotifications()

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
