// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import kotlin.math.roundToInt

/** The animated visualizer at origin 0,0, drawn by the overlay canvas every visualizer frame. */
internal fun DrawScope.drawVisualizerBars(
    skin: Skin,
    s: WinampState,
    box: VisBox = VisBox.FULL,
) {
    @Suppress("UNUSED_EXPRESSION")
    s.visFrame // subscribe to per-frame invalidation
    val colors = skin.visColors
    drawRect(colors[0], Offset.Zero, Size(box.w.toFloat(), box.h.toFloat()))
    if (s.transport == Transport.Stopped || s.visMode == VisMode.Off) return
    // the dotted grid behind the signal, colors[1] on a 2px lattice (webamp preRenderBg)
    if (box.dots) {
        for (gx in 0 until box.w step 2) {
            for (gy in 1 until box.h step 2) {
                drawRect(colors[1], Offset(gx.toFloat(), gy.toFloat()), Size(1f, 1f))
            }
        }
    }
    when (s.visMode) {
        VisMode.Analyzer -> {
            drawAnalyzer(colors, s, box)
        }

        VisMode.Oscilloscope -> {
            drawOscilloscope(colors, s, box)
        }

        VisMode.Off -> {}
    }
}

private fun DrawScope.drawAnalyzer(
    colors: List<Color>,
    s: WinampState,
    box: VisBox,
) {
    for (i in 0 until box.bars) {
        val band = bandsFor(i, box)
        val level = band.maxOf { s.visLevels[it] }.roundToInt().coerceIn(0, Dest.VIS_H)
        val h = rowsFor(level, box)
        val barX = i * box.pitch
        val w = (box.barW).coerceAtMost(box.w - barX)
        if (w <= 0) break
        for (py in (box.h - h) until box.h) {
            drawRect(colors[colorFor(py, box)], Offset(barX.toFloat(), py.toFloat()), Size(w.toFloat(), 1f))
        }
        // the peak cap, drawn in the strip as in the full window
        val peak = band.maxOf { s.visPeaks[it] }
        if (peak >= 0.5f) {
            val py = (box.h - rowsFor(peak.roundToInt(), box)).coerceIn(0, box.h - 1)
            drawRect(colors[23], Offset(barX.toFloat(), py.toFloat()), Size(w.toFloat(), 1f))
        }
    }
}

/**
 * Which of the nineteen measured bands one bar stands for.
 *
 * The shaded strip shows ten bars. As in Winamp, the whole spectrum is resampled into them
 * (`targetSize` in webamp's VisPainter is 40 for window shade against 75).
 */
private fun bandsFor(
    bar: Int,
    box: VisBox,
): IntRange {
    if (box.bars >= BANDS) return bar..bar
    val from = bar * BANDS / box.bars
    val until = ((bar + 1) * BANDS / box.bars).coerceAtLeast(from + 1)
    return from..(until - 1).coerceAtMost(BANDS - 1)
}

/** How many bands the analyzer measures. */
private const val BANDS = 19

/** A level measured against the full 16 rows, in this box's rows. */
private fun rowsFor(
    level: Int,
    box: VisBox,
): Int = if (box.h == Dest.VIS_H) level else (level * box.h + Dest.VIS_H / 2) / Dest.VIS_H

/**
 * Which VISCOLOR entry a row of a bar uses.
 *
 * The full window's sixteen rows use entries 2..17, top to bottom. The five-row strip uses
 * Winamp's separate window-shade ramp: 4, 8, 11, 14, 17 from the top down (webamp's
 * `colorssmall`).
 */
private fun colorFor(
    py: Int,
    box: VisBox,
): Int = if (box.h == Dest.VIS_H) FIRST_BAR_COLOR + py else SHADE_RAMP[py.coerceIn(0, SHADE_RAMP.size - 1)]

/** VISCOLOR entry 2 is the top row of a full-height bar. */
private const val FIRST_BAR_COLOR = 2

/** Winamp's window-shade ramp, top row first. */
private val SHADE_RAMP = intArrayOf(4, 8, 11, 14, 17)

/**
 * Winamp's "lines" oscilloscope, transcribed from webamp's WavePaintHandler: adjacent columns
 * are connected by a vertical run (with Winamp's top++ when ascending), colored by the
 * current column's distance from center with the VISCOLOR 18..22 ramp. In the full window
 * everything is pushed down 2 rows and clipped at the bottom.
 */
private fun DrawScope.drawOscilloscope(
    colors: List<Color>,
    s: WinampState,
    box: VisBox,
) {
    val small = box.h != Dest.VIS_H
    var lastY = s.visWave[0]
    // as in Winamp (webamp's VisPainter), the wave is not squeezed sideways: as many
    // columns are drawn as the box is wide
    for (x in 0 until minOf(box.w, WinampState.WAVE_COLUMNS)) {
        val y = s.visWave[x]
        var top = y
        var bottom = lastY
        lastY = y
        if (bottom < top) {
            val t = top
            top = bottom
            bottom = t
            top++ // emulates Winamp's OSC ascending-line behavior
        }
        val color = colors[18 + oscColorIndex(y)]
        for (row in top..bottom) {
            // the shaded strip scales the rows to its height and has no push-down
            val py = if (small) row * box.h / Dest.VIS_H else row + OSC_PUSH_DOWN
            if (py >= box.h) break
            drawRect(color, Offset(x.toFloat(), py.toFloat()), Size(1f, 1f))
        }
    }
}

private const val OSC_PUSH_DOWN = 2

/** VISCOLOR 18..22 selection by distance from the scope's center (webamp colorIndex). */
internal fun oscColorIndex(y: Int): Int =
    when {
        y >= 14 -> 4
        y >= 12 -> 3
        y >= 10 -> 2
        y >= 8 -> 1
        y >= 6 -> 0
        y >= 4 -> 1
        y >= 2 -> 2
        else -> 3
    }
