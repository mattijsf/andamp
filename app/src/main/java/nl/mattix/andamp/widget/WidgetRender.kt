// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.VisBox
import nl.mattix.andamp.wal.WalStill
import nl.mattix.walkit.ClassicVisMode

/**
 * The player, as a picture the launcher can hold.
 *
 * RemoteViews takes a bitmap, so the skin's main window is drawn off screen by the engine the
 * player draws it with ([WalStill]), at 1x, and scaled up by the widget's factor without smoothing.
 * Every picture of one send is drawn at the same moment of the skin's clock ([atMs]), so a patch is
 * the window's own pixels.
 *
 * The scale is uniform for the whole window and never per sprite (ENGINEERING.md, Pixel rule 2).
 * Whether it is a whole number is the listener's choice; see [WidgetLayout].
 */
internal object WidgetRender {
    fun bitmap(
        still: WalStill,
        layout: WidgetLayout,
        state: WinampState,
        atMs: Long,
    ): Bitmap = whole(still, layout, stillOf(state, atMs, layout.shaded))

    /**
     * One layer of the window, drawn on its own so it can be sent on its own: the whole window,
     * cut to the layer's rectangle, so there is one way of drawing these pixels.
     */
    fun layer(
        still: WalStill,
        layout: WidgetLayout,
        state: WinampState,
        part: WidgetLayer,
        atMs: Long,
    ): Bitmap =
        cut(whole(still, layout, stillOf(state, atMs, shaded = false)), layout.rect(part.left, part.top, part.width, part.height))

    /**
     * The visualizer, as a short strip of pictures for the launcher to flip.
     *
     * Every frame a widget draws is a bitmap crossing a process boundary, so the frames are handed
     * over in a batch and the launcher's ViewFlipper cycles them. A ViewFlipper stops flipping when
     * its window is not visible. The skin's clock steps a frame's time between them, from one frame
     * after [atMs], so the skin reads each frame's audio and the bars fall as they do on the player.
     *
     * Each frame is the visualizer alone, [VisBox.FULL] in size.
     */
    fun visualizerFrames(
        still: WalStill,
        layout: WidgetLayout,
        state: WinampState,
        frames: List<VisFrame>,
        mode: VisMode,
        atMs: Long,
    ): List<Bitmap> {
        val at = visualizerRect(layout)
        return frames.mapIndexed { i, frame ->
            val moment =
                stillOf(state, atMs + (i + 1) * FRAME_STEP_MS, shaded = false).copy(
                    vis = visOf(mode),
                    spectrum = frame.spectrum,
                    waveform = frame.waveform,
                )
            cut(whole(still, layout, moment), at)
        }
    }

    /** Where the visualizer's strip goes; the frames and the view they are flipped in share it. */
    fun visualizerRect(layout: WidgetLayout) = layout.rect(Dest.VISUALIZER.x, Dest.VISUALIZER.y, VisBox.FULL.w, VisBox.FULL.h)

    /** The time between two frames of the strip, as they were captured. */
    private const val FRAME_STEP_MS = WidgetOps.FRAME_MS * WidgetOps.KEEP_EVERY

    /** What [state] shows, at [atMs] on the skin's clock. */
    internal fun stillOf(
        state: WinampState,
        atMs: Long,
        shaded: Boolean,
    ): WalStill.Still = WalStill.stillOf(state, atMs, shaded)

    private fun visOf(mode: VisMode) =
        when (mode) {
            VisMode.Analyzer -> ClassicVisMode.ANALYZER
            VisMode.Oscilloscope -> ClassicVisMode.SCOPE
            VisMode.Off -> ClassicVisMode.OFF
        }

    /** The window as [still] has it, the widget's size, or an empty picture when the skin draws nothing. */
    private fun whole(
        engine: WalStill,
        layout: WidgetLayout,
        still: WalStill.Still,
    ): Bitmap {
        val out = Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888)
        val drawn = engine.main(still)?.asAndroidBitmap() ?: return out
        val scale = Matrix().apply { setScale(layout.factor, layout.factor) }
        Canvas(out).drawBitmap(drawn, scale, Paint().apply { isFilterBitmap = false })
        return out
    }

    /** [whole] cut to [at], kept inside it. */
    private fun cut(
        whole: Bitmap,
        at: WidgetLayout.Rect,
    ): Bitmap {
        val x = at.x.coerceIn(0, whole.width - 1)
        val y = at.y.coerceIn(0, whole.height - 1)
        return Bitmap.createBitmap(whole, x, y, at.w.coerceAtMost(whole.width - x), at.h.coerceAtMost(whole.height - y))
    }
}
