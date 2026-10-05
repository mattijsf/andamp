// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore

/**
 * The height of a shaded window: a window collapsed to its title bar.
 *
 * The shaded windows' coordinates are webamp's (main-window.css, equalizer-window.css and
 * playlist-window.css under `.shade`, and the SHADE sprites in skinSprites.ts). The
 * transport in the shaded player is part of the background art, so those widgets are hit
 * rectangles with no pressed art.
 */
const val SHADE_H = 14

/**
 * The center-relative offset that puts the top edge of a window [height] tall on [row].
 */
fun dockedOffset(
    row: Int,
    height: Int,
    screenH: Int,
) = IntOffset(0, row - (screenH - height) / 2)

/** A window's height, given whether it is collapsed. */
fun heightOf(
    full: Int,
    shaded: Boolean,
) = if (shaded) SHADE_H else full

/**
 * Which of the three thumb arts a value shows: one each for the low, middle and high third
 * of a slider's travel (webamp's `segment()`).
 */
internal fun <T> segmentOf(
    fraction: Float,
    low: T,
    middle: T,
    high: T,
): T =
    when {
        fraction < 1 / 3f -> low
        fraction < 2 / 3f -> middle
        else -> high
    }

/** The shaded clock: `mm:ss` with padded minutes, so its width is constant. */
internal fun shadeTime(s: WinampState): String {
    val duration = s.currentTrack?.durationSec ?: 0
    val shown = if (s.timeRemaining) duration - s.currentTimeSec else s.currentTimeSec
    val sec = shown.coerceAtLeast(0)
    return "%02d:%02d".format(sec / 60, sec % 60)
}

/**
 * Collapses or expands a window, leaving its title bar where it is.
 *
 * A window's place is a center-relative offset, so a window that has been moved is
 * re-anchored for its new height, and the windows docked under it follow. A window that has
 * never been moved is placed by its row. In a locked stack the layout places every window,
 * and the places they float at are left as they are.
 */
fun WinampState.setShaded(
    id: String,
    shaded: Boolean,
    expandedH: Int,
) {
    // refused here for every caller while the shade is switched off
    if (shaded && !shadeEnabled) return
    val was = isShaded(id)
    if (was == shaded) return
    val oldH = heightOf(expandedH, was)
    val newH = heightOf(expandedH, shaded)
    if (!doubleSize) {
        val offset = offsetInState(id)
        if (offset != null && screenH > 0) {
            placeWindow(id, WindowSizing.anchorTop(offset, oldH = oldH, newH = newH, screenH = screenH))
        }
        carryDockedBelow(id, newH - oldH)
    }
    when (id) {
        WindowStore.MAIN -> mainShaded = shaded
        WindowStore.EQ -> eqShaded = shaded
        WindowStore.PLAYLIST -> plShaded = shaded
    }
}

/**
 * Expands every shaded window, through [setShaded], so a moved window keeps its title bar
 * where it is.
 */
fun WinampState.unshadeEverything(playlistExpandedH: Int) {
    setShaded(WindowStore.MAIN, false, MAIN_H)
    setShaded(WindowStore.EQ, false, EQ_H)
    setShaded(WindowStore.PLAYLIST, false, playlistExpandedH)
}

/** Whether [id] is collapsed right now. */
fun WinampState.isShaded(id: String) =
    when (id) {
        WindowStore.MAIN -> mainShaded
        WindowStore.EQ -> eqShaded
        WindowStore.PLAYLIST -> plShaded
        else -> false
    }
