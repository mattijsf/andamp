// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.SkinCut
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button

/**
 * A floating window in the current skin's generic frame (GEN.BMP), the chrome Winamp used
 * for windows other than the player, the equalizer and the playlist. The skin manager and
 * the media library use it.
 *
 * Callers supply a title, a size, their widgets and a draw block, and get the frame and its
 * close button. Dragging, docking and the resize grip are [FloatingSkinWindow]'s.
 *
 * Widget coordinates are window-relative, so a widget at (0,0) sits in the frame's top-left
 * corner. [drawContent] draws in the content area's own coordinates and is clipped to it.
 */
@Composable
fun GenSkinWindow(
    skin: Skin,
    state: WinampState,
    scale: Int,
    spec: GenWindowSpec,
    widgets: List<Widget>,
    modifier: Modifier = Modifier,
    drawContent: DrawScope.() -> Unit = {},
) {
    val frame = frameFor(skin)
    val title = spec.title
    val width = spec.width
    val onClose by rememberUpdatedState(spec.onClose)
    // the frame's close button is a widget, in the same list as the caller's
    val close = remember(title, width, frame) { genCloseWidget(title, width, frame) { onClose() } }
    val allWidgets = remember(close, widgets) { listOf(close) + widgets }
    FloatingSkinWindow(
        id = spec.id,
        state = state,
        scale = scale,
        width = width,
        height = spec.height,
        offset = spec.offset,
        defaultOffset = spec.offset,
        onMove = spec.onMove,
        titleH = frame.titleH,
        widgets = allWidgets,
        onResizeRaw = spec.onResizeRaw,
        cut = SkinCut(skin),
        modifier = modifier,
    ) {
        with(frame) { draw(skin, width, spec.height, title, state.pressedWidget == closeWidgetId(title)) }
        // content draws in its own space, clipped to the frame's hole
        clipRect(
            frame.leftW.toFloat(),
            frame.titleH.toFloat(),
            (frame.leftW + width - frame.chromeW).toFloat(),
            (frame.titleH + spec.height - frame.chromeH).toFloat(),
        ) {
            translate(frame.leftW.toFloat(), frame.titleH.toFloat()) {
                drawContent()
            }
        }
    }
}

/** The id of a generic window's close button, derived from its title. */
fun closeWidgetId(title: String): String = "gen.${title.lowercase().replace(' ', '.')}.close"

/** The close button of a generic window, in window coordinates. */
fun genCloseWidget(
    title: String,
    width: Int,
    frame: WindowFrame,
    onClose: () -> Unit,
): Widget =
    button(
        closeWidgetId(title),
        frame.closeX(width),
        frame.closeY(),
        GenWindow.CLOSE_W,
        GenWindow.CLOSE_W,
        onTap = onClose,
    )
