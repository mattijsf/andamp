// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin

/**
 * A skin's REGION.TXT as a shape to clip a window with.
 *
 * A skin with a region paints the parts of its art outside the region in a filler color,
 * which shows unless the window is cut. The cut-away parts also take no touches, so what
 * is behind them can be reached.
 */
object RegionShape {
    /** The shape for [window] in [skin], or null when the skin cuts nothing. */
    fun of(
        skin: Skin,
        window: RegionTxt.Window,
        scale: Int,
    ): Shape? {
        val polygons = skin.regions[window]?.takeIf { it.isNotEmpty() } ?: return null
        return object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline = Outline.Generic(pathOf(polygons, scale))
        }
    }

    /**
     * The shape for a window the region format has no section for, cut with the skin's
     * corner; null when the skin declares none. The outline is built for the size the
     * window is measured at, so one corner serves a window of any size.
     */
    fun corners(
        corner: RegionTxt.Polygon?,
        scale: Int,
    ): Shape? {
        if (corner == null) return null
        return object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline = Outline.Generic(cornerPath(corner, size, scale))
        }
    }

    /**
     * One corner mirrored into four, as a path around a box of [size].
     *
     * The corner's points run from the left edge round to the top. The top-left corner uses
     * them as they are and the other three mirrored, walked clockwise, so the path's own
     * segments between corners are the window's straight edges.
     */
    fun cornerPath(
        corner: RegionTxt.Polygon,
        size: Size,
        scale: Int,
    ): Path {
        val w = size.width
        val h = size.height
        val points = corner.points
        val path = Path()

        fun step(
            at: Int,
            x: Float,
            y: Float,
        ) = if (at == 0) path.moveTo(x, y) else path.lineTo(x, y)
        points.forEachIndexed { at, p -> step(at, p.x * scale.toFloat(), p.y * scale.toFloat()) }
        points.reversed().forEach { p -> path.lineTo(w - p.x * scale, p.y * scale.toFloat()) }
        points.forEach { p -> path.lineTo(w - p.x * scale, h - p.y * scale) }
        points.reversed().forEach { p -> path.lineTo(p.x * scale.toFloat(), h - p.y * scale) }
        path.close()
        return path
    }

    /** The polygons as one path, in device pixels. */
    fun pathOf(
        polygons: List<RegionTxt.Polygon>,
        scale: Int,
    ): Path {
        val path = Path()
        polygons.forEach { polygon ->
            polygon.points.forEachIndexed { at, point ->
                val x = (point.x * scale).toFloat()
                val y = (point.y * scale).toFloat()
                if (at == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        return path
    }
}
