// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.drop
import nl.mattix.andamp.state.WinampState

/**
 * Ticks under the finger when a balance or preamp it drags snaps to the middle or 0 dB, in the
 * classic skin and a modern one alike, as a slider with a detent would.
 */
@Composable
internal fun SnapTicks(state: WinampState) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        // the count the surface finds when it shows is no snap
        snapshotFlow { state.snaps }.drop(1).collect { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
}
