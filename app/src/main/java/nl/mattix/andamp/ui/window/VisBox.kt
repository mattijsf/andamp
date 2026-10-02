// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.skin.Dest

/**
 * The box the in-player visualizer draws in. There are two: the full window's, and the
 * five-row strip the title bar keeps when the window is shaded, half as wide and without
 * the dotted lattice (webamp css/main-window.css: `.shade #visualizer` at 79,5;
 * js/components/Vis.tsx for the sizes).
 */
internal data class VisBox(
    val w: Int,
    val h: Int,
    /** How many bars the spectrum is shown as; the bands are resampled to fit. */
    val bars: Int,
    /** How wide one analyzer bar is, and how far to the next one. */
    val barW: Int,
    val pitch: Int,
    /** Whether the dotted lattice is drawn behind the signal. */
    val dots: Boolean,
) {
    companion object {
        val FULL = VisBox(Dest.VIS_W, Dest.VIS_H, bars = 19, barW = 3, pitch = 4, dots = true)

        /**
         * The shaded title bar's strip, at x=79 y=5: ten bars of the same width and pitch
         * as the full window's, five rows high, after webamp's VisPainter.
         */
        val SHADE = VisBox(38, 5, bars = 10, barW = 3, pitch = 4, dots = false)
    }
}
