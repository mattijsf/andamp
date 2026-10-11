// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.rowLabel
import nl.mattix.andamp.ui.PlayerViewport
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.window.ListText
import nl.mattix.andamp.ui.window.PlaylistTextRasterizer
import nl.mattix.andamp.ui.window.drawPlaylistSides
import nl.mattix.andamp.ui.window.drawPlaylistTop
import nl.mattix.andamp.ui.window.formatTime
import kotlin.math.roundToInt

/**
 * The playlist on a lent surface, to pick a song from. Each row has a play button, and a tap
 * on that plays the row. The rest of a row takes no touch, so a finger that lands on the list
 * to move it plays nothing, and nothing here changes the list. A drag reported by the
 * surface's owner scrolls it through [scroll], and the close button calls [onClose].
 *
 * It fills [room], which is in the pixels of [fit]'s surface, and opens with the row that is
 * on in the middle.
 */
@Composable
@Suppress("LongParameterList") // a window: its player, its skin, its room and what it reports
internal fun RemoteList(
    vm: WinampViewModel,
    skin: Skin,
    room: IntRect,
    fit: PlayerViewport,
    scroll: ListScroll,
    onClose: () -> Unit,
) {
    val s = vm.state
    val scale = fit.scale
    // the surface's owner counts a drag in the surface's pixels, of which a pixel of the
    // window takes this many
    val perPixel = scale * fit.shrink
    val assets = LocalContext.current.assets
    val touchPx = with(LocalDensity.current) { RemoteListLayout.TOUCH_DP.dp.roundToPx() }
    val layout = remember(room, scale, touchPx) { RemoteListLayout(room.width, room.height, scale, touchPx) }
    val arialLike = remember { Typeface.createFromAsset(assets, PlaylistTextRasterizer.ASSET_PATH) }
    val paint =
        remember(skin.pledit.fontName, layout.fontSize) {
            ListText.paint(ListText.typefaceFor(skin.pledit.fontName, arialLike)).apply { textSize = layout.fontSize }
        }
    var pressed by remember { mutableIntStateOf(NO_ROW) }
    val rows = s.playlist.size

    LaunchedEffect(layout) {
        scroll.bound(layout.farthest(rows) * perPixel)
        scroll.moveTo(layout.showing(s.currentIndex, rows) * perPixel)
    }
    LaunchedEffect(layout, rows) { scroll.bound(layout.farthest(rows) * perPixel) }
    val decay = rememberSplineBasedDecay<Float>()
    LaunchedEffect(scroll.thrown) {
        val thrown = scroll.thrown ?: return@LaunchedEffect
        AnimationState(scroll.offset, thrown.velocity).animateDecay(decay) {
            scroll.moveTo(value)
            if (scroll.atAnEnd) cancelAnimation()
        }
        scroll.rest()
    }

    val widgets =
        remember(vm, layout, perPixel) {
            val reach = layout.closeReach
            listOf(
                button(CLOSE, reach.left, reach.top, reach.width, reach.height, onTap = onClose),
                rowsWidget(vm, layout, scroll, rowAt = { y -> layout.rowAt(y, scroll.offset / perPixel) }) { pressed = it },
            )
        }
    Box(Modifier.offset { IntOffset(room.left + (room.width - layout.width * scale) / 2, room.top) }) {
        ScaledWindowCanvas(layout.width, layout.height, scale, s, widgets) {
            drawRemoteList(skin, s, layout, scroll.offset / perPixel, pressed, paint)
        }
    }
}

/**
 * The rows as one control. A press on a row's play button shows there, and a release on the
 * same button plays the row. A touch that lands while the list is still travelling only
 * stops it.
 */
private fun rowsWidget(
    vm: WinampViewModel,
    layout: RemoteListLayout,
    scroll: ListScroll,
    /** The row under a height of the window, as the list is scrolled now. */
    rowAt: (Float) -> Int,
    /** Told which row's play button is held down, or [NO_ROW] for none. */
    onPress: (Int) -> Unit,
): Widget {
    val s = vm.state
    var down = NO_ROW

    /** The row whose play button is under [at], or [NO_ROW]. */
    fun buttonAt(at: Offset): Int = rowAt(at.y).takeIf { layout.onPlay(at.x) && it in s.playlist.indices } ?: NO_ROW
    return Widget(
        id = ROWS,
        bounds = IntRect(layout.listLeft, layout.listTop, layout.listRight, layout.height),
        pointer =
            Widget.Pointer(
                onDown = { at ->
                    val stopping = scroll.thrown != null
                    scroll.rest()
                    down = if (stopping) NO_ROW else buttonAt(at)
                    onPress(down)
                },
                onUp = { at, inside ->
                    if (inside && down != NO_ROW && buttonAt(at) == down) vm.playTrack(down)
                    down = NO_ROW
                    onPress(NO_ROW)
                },
            ),
    )
}

@Suppress("LongParameterList") // one picture: the skin, the state, the geometry and the touch
private fun DrawScope.drawRemoteList(
    skin: Skin,
    s: WinampState,
    layout: RemoteListLayout,
    /** How far the list is scrolled, in the window's pixels. */
    offset: Float,
    pressed: Int,
    paint: Paint,
) {
    val style = skin.pledit
    val listW = (layout.listRight - layout.listLeft).toFloat()
    drawPlaylistTop(skin, s, layout.width)
    drawPlaylistSides(skin, layout.width, layout.listTop, layout.height)
    drawRect(style.normalBg, Offset(layout.listLeft.toFloat(), layout.listTop.toFloat()), Size(listW, layout.listH.toFloat()))

    clipRect(layout.listLeft.toFloat(), layout.listTop.toFloat(), layout.listRight.toFloat(), layout.height.toFloat()) {
        val first = (offset / layout.rowH).toInt()
        val last = minOf(s.playlist.lastIndex, layout.rowAt(layout.height.toFloat(), offset))
        for (row in first..last) {
            val top = layout.listTop + row * layout.rowH - offset
            val color = if (row == s.currentIndex) style.current else style.normal
            if (row == pressed) {
                val side = layout.rowH.toFloat()
                drawRect(style.selectedBg, Offset(layout.listLeft.toFloat(), top), Size(side, side))
            }
            drawPlayButton(layout, top, color)
            paint.color = color.toArgb()
            drawRow(s, layout, row, top, paint)
        }
    }

    // the scrollbar's handle, which shows where the list is and takes no touch
    val handle = SpriteMap.PLAYLIST_SCROLL_HANDLE
    val farthest = layout.farthest(s.playlist.size)
    val along = if (farthest == 0) 0f else offset / farthest
    sprite(
        skin[Sheet.PLEDIT],
        handle,
        layout.width - Dest.PL_RIGHT_W + 5,
        layout.listTop + (along * (layout.listH - handle.h)).roundToInt(),
    )
}

/** A row's play button: a triangle in the row's own color, in the middle of its square. */
private fun DrawScope.drawPlayButton(
    layout: RemoteListLayout,
    top: Float,
    color: Color,
) {
    val side = layout.rowH.toFloat()
    val midX = layout.listLeft + side / 2
    val midY = top + side / 2
    val arrow =
        Path().apply {
            moveTo(midX - side * PLAY_HALF_W, midY - side * PLAY_HALF_H)
            lineTo(midX + side * PLAY_HALF_W, midY)
            lineTo(midX - side * PLAY_HALF_W, midY + side * PLAY_HALF_H)
            close()
        }
    drawPath(arrow, color)
}

/** One row's text: its number and name after the play button, cut off before its length on the right. */
private fun DrawScope.drawRow(
    s: WinampState,
    layout: RemoteListLayout,
    row: Int,
    top: Float,
    paint: Paint,
) {
    val track = s.playlist[row]
    val baseline = ListText.baseline(paint, top, layout.rowH)
    val length = formatTime(track.durationSec)
    val lengthX = layout.listRight - ROW_INSET - paint.measureText(length)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.drawText(length, lengthX, baseline, paint)
        native.save()
        native.clipRect(layout.playRight.toFloat(), top, lengthX - 2 * ROW_INSET, top + layout.rowH)
        native.drawText("${row + 1}. ${s.rowLabel(track, row)}", layout.playRight.toFloat(), baseline, paint)
        native.restore()
    }
}

private const val CLOSE = "pl.close"
private const val ROWS = "remote.rows"
private const val NO_ROW = -1

/** The margin between a row's length and the frame, and half of that between its name and its length. */
private const val ROW_INSET = 6f

/** The play arrow's half width and half height, as shares of its square's side. */
private const val PLAY_HALF_W = 0.17f
private const val PLAY_HALF_H = 0.22f
