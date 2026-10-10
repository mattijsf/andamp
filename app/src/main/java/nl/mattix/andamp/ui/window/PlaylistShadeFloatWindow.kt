// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.SkinCut

/**
 * The playlist collapsed to its bar.
 *
 * As in Winamp, it keeps one grip: a 9x9 patch beside the shade button that widens the
 * playlist. The height cannot change, so the resize runs with a fixed height axis.
 *
 * The bar's controls are anchored to its right edge, as in webamp's `playlist-window.css`.
 */
@Composable
@Suppress("LongParameterList") // a window: its player, its art, its size and its place
fun PlaylistShadeFloatWindow(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    /** The height the playlist expands back to. */
    expandedH: Int,
    /** Its top-left corner until it is moved; see [FloatingSkinWindow]. */
    docked: IntOffset,
    modifier: Modifier = Modifier,
    /** The rectangle the layout holds it in, at its narrowest, or null while it floats. */
    pinned: IntRect? = null,
) {
    val s = vm.state
    BoxWithConstraints(modifier) {
        val screen = windowScreen(scale, s)
        val cols = if (pinned != null) 0 else PlaylistLayout.WIDTH_STEPS.clamp(s.plCols, screen.width)
        val width = PlaylistLayout.widthOfCols(cols)
        FloatingSkinWindow(
            id = WindowStore.PLAYLIST,
            state = s,
            scale = scale,
            width = width,
            height = SHADE_H,
            offset = s.plOffset,
            docked = docked,
            onMove = { s.plOffset = it },
            cut = SkinCut(skin),
            pinned = pinned,
            onResizeRaw = { grab ->
                val resized =
                    WindowSizing.resize(
                        grab,
                        screen,
                        // shaded, the height is fixed
                        heightAxis = SizeAxis(furniture = SHADE_H, step = 1, min = 0, max = 0, current = 0),
                        heightOf = { SHADE_H },
                        widthAxis = PlaylistLayout.WIDTH_STEPS.axis(cols, screen.width),
                        widthOf = { steps -> PL_W + steps * PlaylistLayout.WIDTH_STEP },
                    )
                s.plCols = resized.cols
                s.plOffset = resized.offset
            },
            gripAt = playlistShadeGrip(width),
            titleH = SHADE_H,
            widgets = remember(vm, expandedH, width) { playlistShadeWidgets(vm, expandedH, width) },
            onTitleDoubleTap = { s.setShaded(WindowStore.PLAYLIST, false, expandedH) },
            modifier = Modifier.matchParentSize(),
        ) { drawPlaylistShade(skin, s, width) }
    }
}
