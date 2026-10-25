// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.Flung
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.RowFling
import nl.mattix.andamp.widget.WidgetSignals

/**
 * The clocks that run only while the screen is started: a thrown list's carry, the title's
 * marquee and the paused-time blink.
 *
 * They are tied to the lifecycle here because a snapshot write from a stopped activity still
 * costs a recomposition and a layout pass: Compose pauses the frame clock at ON_STOP but not
 * recomposition, and each write costs the main thread a frame. With [followsScreen] they also stop
 * while the phone's screen is dark, as the floating player stays started then; a surface on another
 * display, such as a car's, does not. They stop too while [shown] says nothing they drive shows,
 * read in a snapshot: each step wakes the main thread for a display frame, even with nothing on
 * screen to redraw. A throw a stop cuts short ends where the list stands.
 *
 * One surface steps them at a time; see [WinampState.clocksTurn]. A surface whose screen goes dark,
 * or that shows nothing they drive, lets go of the turn, so another can take it.
 */
@Composable
internal fun UiClocks(
    state: WinampState,
    followsScreen: Boolean = false,
    shown: () -> Boolean = { true },
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val shownNow by rememberUpdatedState(shown)
    LaunchedEffect(state, followsScreen) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val screenOn = if (followsScreen) WidgetSignals.screenOn(context) else flowOf(true)
            combine(screenOn, snapshotFlow { shownNow() }) { on, seen -> on && seen }
                .distinctUntilChanged()
                .collectLatest { run ->
                    if (run) state.clocksTurn.withLock { coroutineScope { stepClocks(state) } }
                }
        }
    }
}

/**
 * The three clocks, one loop each; they run until the scope is cancelled. The thrown
 * list's carry asks for display frames only while a list is thrown.
 */
internal fun CoroutineScope.stepClocks(state: WinampState) {
    launch {
        // a thrown list carries on after the finger; one loop serves every list,
        // since only one can be thrown at a time
        snapshotFlow { state.flung != null }.collectLatest { thrown ->
            if (thrown) carry(state)
        }
    }
    launch {
        // the title steps a glyph at a time, stands still under a finger, and
        // waits MARQUEE_RESUME_STEPS steps after the finger leaves, after webamp's
        // Marquee: "Resume stepping 1 second after dragging ceases"
        var resting = 0
        while (true) {
            delay(MARQUEE_STEP_MS)
            when {
                state.marqueeHeld -> resting = MARQUEE_RESUME_STEPS
                resting > 0 -> resting--
                else -> state.marqueeStep++
            }
        }
    }
    launch {
        try {
            snapshotFlow { state.transport }.collectLatest { transport ->
                if (transport != Transport.Paused) {
                    state.blinkOn = true
                    return@collectLatest
                }
                while (true) {
                    state.blinkOn = !state.blinkOn
                    delay(BLINK_MS)
                }
            }
        } finally {
            // stopped mid-blink, the time would come back hidden
            state.blinkOn = true
        }
    }
}

/**
 * Carries the thrown list on every display frame until the throw comes to rest and lets go of it.
 * Stopped before then, as when the clocks stop, it lets go of the throw where the list stands, so
 * the throw is not carried again from its start when they run again.
 */
private suspend fun carry(state: WinampState) {
    var carrying: Flung? = null
    var threwAt = 0L
    try {
        while (state.flung != null) {
            withFrameMillis { now ->
                val flung = state.flung ?: return@withFrameMillis
                // the throw is timed from the first frame that sees it,
                // on this loop's clock
                if (flung !== carrying) {
                    carrying = flung
                    threwAt = now
                }
                val since = now - threwAt
                flung.carry(flung.from + RowFling.travelled(flung.rowsPerSecond, since).toInt())
                if (since >= RowFling.restsAfter(flung.rowsPerSecond)) state.flung = null
            }
        }
    } finally {
        // a throw made after the one carried here waits for the next carry
        if (carrying == null || state.flung === carrying) state.flung = null
    }
}

/** The pause before the title scrolls again after a drag, in marquee steps (about 1 s). */
private const val MARQUEE_RESUME_STEPS = 5

/** The time the marquee takes to step one glyph. Shared with the skin preview's own clock. */
internal const val MARQUEE_STEP_MS = 220L

internal const val BLINK_MS = 1000L
