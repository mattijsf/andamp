// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    /** The row it sits on until it is moved. */
    dockedTop: Int,
    modifier: Modifier = Modifier,
    /** The column its left edge sits on until then, or null for the middle of the screen. */
    dockedLeft: Int? = null,
    /** Where the layout holds it, at its narrowest, or null while it floats. */
    pinnedAt: IntOffset? = null,
) {
    val s = vm.state
    val maxCols = ((s.screenW - PL_W) / PlaylistLayout.WIDTH_STEP).coerceAtLeast(0)
    val cols = if (pinnedAt != null) 0 else s.plCols.coerceIn(0, maxCols)
    val width = PL_W + cols * PlaylistLayout.WIDTH_STEP
    FloatingSkinWindow(
        id = WindowStore.PLAYLIST,
        state = s,
        scale = scale,
        width = width,
        height = SHADE_H,
        offset = s.plOffset,
        defaultOffset = s.screen().offsetOf(IntOffset(dockedLeft ?: (s.screenW - width) / 2, dockedTop), IntSize(width, SHADE_H)),
        onMove = { s.plOffset = it },
        cut = SkinCut(skin),
        pinnedAt = pinnedAt,
        onResizeRaw = { grab ->
            val resized =
                WindowSizing.resize(
                    grab,
                    s.screen(),
                    // shaded, the height is fixed
                    heightAxis = SizeAxis(furniture = SHADE_H, step = 1, min = 0, max = 0, current = 0),
                    heightOf = { SHADE_H },
                    widthAxis =
                        SizeAxis(
                            furniture = PL_W,
                            step = PlaylistLayout.WIDTH_STEP,
                            min = 0,
                            max = maxCols,
                            current = cols,
                        ),
                    widthOf = { steps -> PL_W + steps * PlaylistLayout.WIDTH_STEP },
                )
            s.plCols = resized.cols
            s.plOffset = resized.offset
        },
        gripAt = playlistShadeGrip(width),
        titleH = SHADE_H,
        widgets = remember(vm, expandedH, width) { playlistShadeWidgets(vm, expandedH, width) },
        onTitleDoubleTap = { s.setShaded(WindowStore.PLAYLIST, false, expandedH) },
        modifier = modifier,
    ) { drawPlaylistShade(skin, s, width) }
}
