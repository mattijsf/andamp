// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Typeface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.SkinCut

/**
 * The playlist as a floating window: dragged by its title bar, resized by the grip in its
 * bottom-right corner, docking against the other windows.
 *
 * It starts under the stack, filling what is left of the height, and stays there until the
 * listener moves or resizes it: a null offset or a null segment count means "wherever the
 * layout puts it".
 *
 * With [pinned] the layout holds it in that rectangle, whatever its height: the last tile
 * of the frame and the last row of the list are cut where the rectangle ends.
 */
@Composable
@Suppress("LongParameterList") // a window: its player, its skin, its scale and its place
fun PlaylistFloatWindow(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    actions: PlaylistMenuActions,
    /** The row the stack ends on: where the playlist sits until it is moved. */
    dockedTop: Int,
    /** How tall it is while it has never been resized: the rest of the screen. */
    dockedSegments: Int,
    modifier: Modifier = Modifier,
    /** The rectangle the layout holds it in, in virtual pixels, or null while it floats. */
    pinned: IntRect? = null,
) {
    val s = vm.state
    val context = LocalContext.current
    val text =
        remember {
            PlaylistTextRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
        }
    val wanted = s.plSegments ?: dockedSegments

    BoxWithConstraints(modifier) {
        val screen = windowScreen(scale, s)
        val segments = wanted
        // the width is limited to the screen's
        val maxCols = ((screen.width - PL_W) / PlaylistLayout.WIDTH_STEP).coerceAtLeast(0)
        val cols = s.plCols.coerceIn(0, maxCols)
        val layout =
            pinned?.let { PlaylistLayout(it.height, it.width) }
                ?: PlaylistLayout.ofSegments(segments, PlaylistLayout.widthOfCols(cols))
        val height = layout.height

        // Where the top edge is asking to be: under the stack while the window has never
        // been moved, and otherwise the row its offset puts it on. Offsets are
        // center-relative, so the row depends on the height it is drawn at.
        val topWanted = s.plOffset?.let { (screen.height - height) / 2 + it.y } ?: dockedTop
        // What the grip may ask for: what fits under this window's own top edge. The
        // drawn height is not capped by where the window is; a window may hang off the
        // bottom as long as its title bar stays inside the safe area.
        val maxSegments = PlaylistLayout.segmentsThatFit((screen.safeBottom - topWanted).coerceAtLeast(0))
        // the docked place is a row; its center-relative offset is computed for the
        // height the playlist has now
        val docked = IntOffset(0, dockedTop - (screen.height - height) / 2)
        val widgets =
            remember(vm, layout, actions) {
                playlistWidgets(
                    vm,
                    layout,
                    actions,
                    onMenuEntry = { entry -> entry.action(vm) },
                    rowMenu = { row ->
                        nl.mattix.andamp.ui.menu.playlistRowMenu(
                            vm,
                            row,
                            nl.mattix.andamp.ui.menu.playlistRowAnchor(
                                rowTop = layout.textTop + (row - s.playlistScroll) * Dest.PL_ROW_H,
                                rowH = Dest.PL_ROW_H,
                                left = layout.textLeft,
                                width = layout.textRight - layout.textLeft,
                            ),
                        )
                    },
                )
            }

        FloatingSkinWindow(
            id = WindowStore.PLAYLIST,
            state = s,
            scale = scale,
            width = layout.width,
            height = height,
            offset = s.plOffset,
            defaultOffset = docked,
            onMove = { place -> s.plOffset = place },
            titleH = Dest.PL_TOP_H,
            widgets = widgets,
            onResizeRaw = { grab ->
                resizeTo(s, grab, cols, maxCols, segments, maxSegments, screen.width, screen.height)
            },
            onTitleDoubleTap = { s.setShaded(WindowStore.PLAYLIST, true, height) },
            cut = SkinCut(skin),
            pinnedAt = pinned?.topLeft,
            modifier = Modifier.matchParentSize(),
        ) {
            drawPlaylistWindow(skin, s, layout, text, scale)
        }
    }
}

/**
 * Resizes both axes from one grip: the width in 25px steps and the height in 29px ones, each
 * quantized on its own and anchored so the top-left corner stays where it was grabbed.
 */
@Suppress("LongParameterList") // one gesture, two axes and what bounds them
private fun resizeTo(
    s: WinampState,
    grab: WindowGrab,
    cols: Int,
    maxCols: Int,
    segments: Int,
    maxSegments: Int,
    screenW: Int,
    screenH: Int,
) {
    val resized =
        WindowSizing.resize(
            grab,
            screenW = screenW,
            screenH = screenH,
            heightAxis =
                SizeAxis(
                    furniture = PlaylistLayout.FURNITURE_H,
                    step = Dest.PL_TILE_STEP,
                    min = PlaylistLayout.MIN_SEGMENTS,
                    max = maxSegments,
                    current = segments,
                ),
            heightOf = { asked -> PlaylistLayout.ofSegments(asked).height },
            widthAxis =
                SizeAxis(
                    furniture = PL_W,
                    step = PlaylistLayout.WIDTH_STEP,
                    min = 0,
                    max = maxCols,
                    current = cols,
                ),
            widthOf = PlaylistLayout::widthOfCols,
        )
    s.plCols = resized.cols
    s.plSegments = resized.steps
    s.plOffset = resized.offset
}
