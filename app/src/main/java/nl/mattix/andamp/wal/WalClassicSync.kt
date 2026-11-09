// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.mattix.andamp.state.MarkPlace
import nl.mattix.andamp.state.SourceForRows
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import nl.mattix.walkit.ClassicSwitches
import nl.mattix.walkit.ClassicVisMode
import nl.mattix.walkit.RowMark
import nl.mattix.walkit.RowMarks

/**
 * Keeps a classic skin walkit draws ([look]) and the app's settings in step, both ways: the time
 * counting down, the equalizer's auto and the vis mode, as the Options and Visualization menus and
 * a press on the skin change them, and the playlist's selection, as the playlist menus change it.
 * The skin's own presses on a row reach the app through its host actions instead. It also follows
 * the app's window shade setting and the marks the playlist shows beside its rows, and keeps which
 * of the playlist and equalizer windows show, so they open again as the app comes back.
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
        launch {
            snapshotFlow { s.shadeEnabled }.collect(look::setClassicShades)
        }
        launch {
            snapshotFlow { rowMarksOf(s) }.distinctUntilChanged().collect(look::setClassicRowMarks)
        }
        launch {
            // the windows the app keeps open reach the skin, and the skin's own PL, EQ and close
            // buttons, and the Window menu, change what the app keeps. The first frame may come
            // before the skin was told, so only a change from one frame to the next is kept
            launch {
                snapshotFlow { openClassicWindows(s) }.distinctUntilChanged().collect(look::setClassicWindows)
            }
            snapshotFlow { look.layout?.classicWindows?.keys }
                .filterNotNull()
                .map { shown -> WalLook.CLASSIC_WINDOWS.filter { it in shown }.toSet() }
                .distinctUntilChanged()
                .drop(1)
                .collect { shown ->
                    s.plVisible = "pledit" in shown
                    s.eqVisible = "equalizer" in shown
                }
        }
        snapshotFlow { ClassicSwitches(s.timeRemaining, s.eqAuto, s.visMode.toSkin()) }
            .distinctUntilChanged()
            .collect(look::setClassicSwitches)
    }
}

/** The classic windows besides the main one that the app keeps open. */
internal fun openClassicWindows(s: WinampState): Set<String> =
    buildSet {
        if (s.plVisible) add("pledit")
        if (s.eqVisible) add("equalizer")
    }

/**
 * The marks of the playlist's rows as a classic skin shows them: each row's music source's mark,
 * where the listener has the playlist show them, before the title or ahead of the length; the
 * picture, or its letter for a mark without one.
 */
internal fun rowMarksOf(s: WinampState): RowMarks {
    if (s.listMarks == MarkPlace.OFF || s.sourceMarks.isEmpty()) return RowMarks.NONE
    val ids = s.sourceMarks.keys.toList()
    val marks = ids.map { id -> s.sourceMarks.getValue(id).let { RowMark(it.pixels, it.letter) } }
    val rows = s.playlist.map { track -> ids.indexOf(SourceForRows.of(track).id).takeIf { it >= 0 } }
    val place = if (s.listMarks == MarkPlace.BEFORE) nl.mattix.walkit.MarkPlace.BEFORE else nl.mattix.walkit.MarkPlace.AFTER
    return RowMarks(marks, rows, place)
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
