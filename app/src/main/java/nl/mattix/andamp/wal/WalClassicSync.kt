// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import nl.mattix.walkit.ClassicSwitches
import nl.mattix.walkit.ClassicVisMode

/**
 * Keeps a classic skin walkit draws ([look]) and the app's settings in step, both ways: the time
 * counting down, the equalizer's auto and the vis mode, as the Options and Visualization menus and
 * a press on the skin change them, and the playlist's selection, as the playlist menus change it.
 * The skin's own presses on a row reach the app through its host actions instead.
 */
@Composable
internal fun WalClassicSync(
    look: WalSurface,
    s: WinampState,
) {
    LaunchedEffect(look, s) {
        // a press on the skin: the first frame after it carries what changed. A press from before
        // this screen showed the skin was heard by the screen that showed it then
        val seen = look.layout?.pressedSwitches?.serial ?: 0
        launch {
            snapshotFlow { look.layout?.pressedSwitches }
                .filterNotNull()
                .filter { it.serial > seen }
                .distinctUntilChanged()
                .collect { (_, switches) ->
                    s.timeRemaining = switches.timeRemaining
                    s.eqAuto = switches.eqAuto
                    s.visMode = switches.visMode.toApp()
                }
        }
        launch {
            snapshotFlow { s.selectedRows }.collect(look::setClassicSelection)
        }
        snapshotFlow { ClassicSwitches(s.timeRemaining, s.eqAuto, s.visMode.toSkin()) }
            .distinctUntilChanged()
            .collect(look::setClassicSwitches)
    }
}

private fun ClassicVisMode.toApp(): VisMode =
    when (this) {
        ClassicVisMode.ANALYZER -> VisMode.Analyzer
        ClassicVisMode.SCOPE -> VisMode.Oscilloscope
        ClassicVisMode.OFF -> VisMode.Off
    }

private fun VisMode.toSkin(): ClassicVisMode =
    when (this) {
        VisMode.Analyzer -> ClassicVisMode.ANALYZER
        VisMode.Oscilloscope -> ClassicVisMode.SCOPE
        VisMode.Off -> ClassicVisMode.OFF
    }
