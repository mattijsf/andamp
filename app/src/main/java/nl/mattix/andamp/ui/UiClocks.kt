// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.Flung
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.RowFling

/**
 * The clocks that run only while the screen is started: a thrown list's carry, the title's
 * marquee and the paused-time blink.
 *
 * They are tied to the lifecycle here because a snapshot write from a stopped activity still
 * costs a recomposition and a layout pass: Compose pauses the frame clock at ON_STOP but not
 * recomposition.
 */
@Composable
internal fun UiClocks(state: WinampState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                // a thrown list carries on after the finger; one loop serves every list,
                // since only one can be thrown at a time
                var carrying: Flung? = null
                var threwAt = 0L
                while (true) {
                    withFrameMillis { now ->
                        val flung = state.flung
                        if (flung == null) {
                            carrying = null
                            return@withFrameMillis
                        }
                        // the throw is timed from the first frame that sees it,
                        // on this loop's own clock
                        if (flung !== carrying) {
                            carrying = flung
                            threwAt = now
                        }
                        val since = now - threwAt
                        flung.carry(flung.from + RowFling.travelled(flung.rowsPerSecond, since).toInt())
                        if (since >= RowFling.restsAfter(flung.rowsPerSecond)) state.flung = null
                    }
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
    }
}

/** The pause before the title scrolls again after a drag, in marquee steps (about 1 s). */
private const val MARQUEE_RESUME_STEPS = 5

/** The time the marquee takes to step one glyph. Shared with the skin preview's own clock. */
internal const val MARQUEE_STEP_MS = 220L

internal const val BLINK_MS = 1000L
