// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.VisBox
import nl.mattix.andamp.ui.window.drawMainShade
import nl.mattix.andamp.ui.window.drawMainWindow
import nl.mattix.andamp.ui.window.drawVisualizerBars

/**
 * The player, as a picture the launcher can hold.
 *
 * RemoteViews takes a bitmap and cannot run Compose, so the window is rendered off-screen with the
 * same draw functions the app uses on screen.
 *
 * The scale is uniform for the whole window and never per sprite (ENGINEERING.md, Pixel rule 2).
 * Whether it is a whole number is the listener's choice; see [WidgetLayout].
 */
object WidgetRender {
    fun bitmap(
        skin: Skin,
        layout: WidgetLayout,
        state: WinampState,
    ): Bitmap {
        val image = ImageBitmap(layout.widthPx, layout.heightPx)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(image),
            Size(layout.widthPx.toFloat(), layout.heightPx.toFloat()),
        ) {
            withTransform({ scale(layout.factor, layout.factor, Offset.Zero) }) {
                if (layout.shaded) drawMainShade(skin, state) else drawMainWindow(skin, state)
            }
        }
        return image.asAndroidBitmap()
    }

    /**
     * One layer of the window, drawn on its own so it can be sent on its own. The whole window is
     * drawn and clipped to the layer's rectangle, so there is one way of drawing these pixels.
     */
    fun layer(
        skin: Skin,
        layout: WidgetLayout,
        state: WinampState,
        part: WidgetLayer,
    ): Bitmap {
        val at = layout.rect(part.left, part.top, part.width, part.height)
        val image = ImageBitmap(at.w, at.h)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(image),
            Size(image.width.toFloat(), image.height.toFloat()),
        ) {
            // shifted by whole device pixels before the scale: the patch's origin is a rounded
            // pixel, which at a fractional factor is not where its virtual coordinate lands, and
            // dividing it back through the factor loses enough precision to move a sprite edge
            // across a pixel boundary
            withTransform({
                translate(-at.x.toFloat(), -at.y.toFloat())
                scale(layout.factor, layout.factor, Offset.Zero)
            }) {
                drawMainWindow(skin, state)
            }
        }
        return image.asAndroidBitmap()
    }

    /**
     * The visualizer, as a short strip of pictures for the launcher to flip.
     *
     * Every frame a widget draws is a bitmap crossing a process boundary, so the frames are handed
     * over in a batch and the launcher's ViewFlipper cycles them. A ViewFlipper stops flipping when
     * its window is not visible.
     *
     * Each frame is the visualizer alone, [VisBox.FULL] in size.
     */
    fun visualizerFrames(
        skin: Skin,
        layout: WidgetLayout,
        state: WinampState,
        frames: List<VisFrame>,
        mode: VisMode,
    ): List<Bitmap> {
        state.visMode = mode
        val box = VisBox.FULL
        val at = visualizerRect(layout)
        return frames.map { frame ->
            frame.applyTo(state)
            val image = ImageBitmap(at.w, at.h)
            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                Canvas(image),
                Size(at.w.toFloat(), at.h.toFloat()),
            ) {
                withTransform({ scale(layout.factor, layout.factor, Offset.Zero) }) {
                    drawVisualizerBars(skin, state, box)
                }
            }
            image.asAndroidBitmap()
        }
    }

    /** Where the visualizer's strip goes; the frames and the view they are flipped in share it. */
    fun visualizerRect(layout: WidgetLayout) = layout.rect(Dest.VISUALIZER.x, Dest.VISUALIZER.y, VisBox.FULL.w, VisBox.FULL.h)
}
