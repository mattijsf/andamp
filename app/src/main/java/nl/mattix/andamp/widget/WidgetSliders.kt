// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.ui.window.SliderMath

/** The two sliders the widget offers. */
enum class WidgetSlider {
    SEEK,
    VOLUME,
}

/** One tap target on a slider, and what pressing it means. */
data class SliderStep(
    val slider: WidgetSlider,
    /** What the player is set to when this step is pressed, 0..1. */
    val fraction: Float,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * The seek bar and the volume slider, cut into tap targets.
 *
 * RemoteViews has no draggable control, so the widget offers a row of tap targets across each
 * track: a press sets the slider to that place.
 *
 * Each step reports the fraction the player's own slider would report for a touch at its center,
 * through the same [SliderMath.horizontalFraction] the drag uses, so the thumb lands under the
 * finger.
 */
object WidgetSliders {
    /**
     * How finely each is cut. Every step is a view and a PendingIntent. Twelve steps cover the seek
     * bar; ten over the volume is about ten percent each.
     */
    const val SEEK_STEPS = 12
    const val VOLUME_STEPS = 10

    private const val POSBAR_W = 248
    private const val POSBAR_THUMB = 29
    private const val VOLUME_W = 68
    private const val VOLUME_THUMB = 14

    /**
     * The touch bands of the sliders, in virtual pixels. The targets are invisible, so they are
     * taller than the ten-pixel seek bar and thirteen-pixel volume art: the volume band runs down
     * to where the seek band starts, and the seek band down to where the transport row starts.
     * WidgetTouchTest holds those edges.
     */
    private const val VOLUME_BAND_TOP = 53
    private const val VOLUME_BAND_H = 18
    private const val POSBAR_BAND_TOP = 71
    private const val POSBAR_BAND_H = 16

    /**
     * The volume thumb's travel in virtual pixels, the value MainWindow passes as `travelOverride`.
     */
    private const val VOLUME_TRAVEL = 51

    /**
     * The steps for [layout], in the bitmap's own pixels. Without [seekable] the seek bar gets no
     * steps: a stream has no position to move to, and a backend that cannot seek would swallow the
     * press. The bar is still drawn.
     */
    fun steps(
        layout: WidgetLayout,
        seekable: Boolean,
        volumeControl: Boolean = false,
    ): List<SliderStep> {
        if (layout.shaded) return emptyList()
        val seek =
            if (!seekable) {
                emptyList()
            } else {
                cut(
                    WidgetSlider.SEEK,
                    Dest.POSBAR.x,
                    POSBAR_BAND_TOP,
                    POSBAR_W,
                    POSBAR_BAND_H,
                    POSBAR_THUMB,
                    POSBAR_W - POSBAR_THUMB,
                    SEEK_STEPS,
                )
            }
        val volume =
            if (!volumeControl) {
                emptyList()
            } else {
                cut(
                    WidgetSlider.VOLUME,
                    Dest.VOLUME.x,
                    VOLUME_BAND_TOP,
                    VOLUME_W,
                    VOLUME_BAND_H,
                    VOLUME_THUMB,
                    VOLUME_TRAVEL,
                    VOLUME_STEPS,
                )
            }
        return (seek + volume).map { step ->
            val at = layout.rect(step.left, step.top, step.width, step.height)
            step.copy(left = at.x, top = at.y, width = at.w, height = at.h)
        }
    }

    @Suppress("LongParameterList") // The parameters are one slider's geometry.
    private fun cut(
        slider: WidgetSlider,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        thumbWidth: Int,
        travel: Int,
        steps: Int,
    ): List<SliderStep> =
        (0 until steps).map { at ->
            // the steps cover the whole track, leaving no gap between them
            val from = left + width * at / steps
            val until = left + width * (at + 1) / steps
            SliderStep(
                slider = slider,
                fraction = SliderMath.horizontalFraction((from + until) / 2f, left, thumbWidth, travel),
                left = from,
                top = top,
                width = until - from,
                height = height,
            )
        }
}
