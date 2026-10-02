// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

/**
 * REGION.TXT: the shape a skin cuts its windows into.
 *
 * Some classic skins draw a window with angled corners or a bite out of one side and paint the
 * leftover pixels a color that is not meant to be seen; Winamp cuts those away with a region.
 *
 * The file is an INI: one section per window, `NumPoints` naming how many points each polygon takes
 * and `PointList` holding them all end to end. Points may be separated by commas or by spaces, and
 * a polygon of fewer than three points is dropped, as in webamp's regionParser.ts.
 */
object RegionTxt {
    /** The windows a region file may name. */
    enum class Window(
        val section: String,
    ) {
        MAIN("normal"),
        MAIN_SHADE("windowshade"),
        EQ("equalizer"),
        EQ_SHADE("equalizerws"),
    }

    /** A closed shape in window-relative virtual pixels. */
    data class Polygon(
        val points: List<Point>,
    )

    data class Point(
        val x: Int,
        val y: Int,
    )

    /** What a skin cuts away; a window with no entry keeps its rectangle. */
    data class Regions(
        val byWindow: Map<Window, List<Polygon>>,
        /** The corner every other window is cut with, or null; see [CORNERS]. */
        val corner: Polygon? = null,
    ) {
        operator fun get(window: Window): List<Polygon>? = byWindow[window]

        val isEmpty: Boolean get() = byWindow.isEmpty() && corner == null

        companion object {
            val NONE = Regions(emptyMap())
        }
    }

    fun parse(text: String?): Regions {
        if (text.isNullOrBlank()) return Regions.NONE
        val sections = ini(text)
        val byWindow =
            Window.entries.mapNotNull { window ->
                val section = sections[window.section] ?: return@mapNotNull null
                val polygons = polygonsOf(section["numpoints"], section["pointlist"])
                if (polygons.isEmpty()) null else window to polygons
            }
        val corners = sections[CORNERS]
        return Regions(
            byWindow.toMap(),
            corner = polygonsOf(corners?.get("numpoints"), corners?.get("pointlist")).firstOrNull(),
        )
    }

    /**
     * `[Corners]`: the cut for the windows Winamp's four sections do not name.
     *
     * Winamp cuts only the player and the equalizer. The playlist and plug-in windows are
     * resizable, so a point list cannot describe them. This section holds one corner, the top-left,
     * from the left edge round to the top, in window-relative virtual pixels, to be mirrored into
     * the other three at the window's size.
     *
     * The section is Andamp's own: a classic skin has none and keeps its rectangles.
     */
    private const val CORNERS = "corners"

    private fun polygonsOf(
        numPoints: String?,
        pointList: String?,
    ): List<Polygon> {
        if (numPoints == null || pointList == null) return emptyList()
        val counts = numPoints.split(SEPARATORS).filter { it.isNotBlank() }.mapNotNull { it.trim().toIntOrNull() }
        val numbers = pointList.split(SEPARATORS).filter { it.isNotBlank() }.mapNotNull { it.trim().toIntOrNull() }
        val points = numbers.chunked(2).filter { it.size == 2 }.map { Point(it[0], it[1]) }
        var at = 0
        return counts.mapNotNull { count ->
            val taken = points.drop(at).take(count)
            at += count
            // a skin may name more polygons than it gave points for, and one
            // with fewer than three points encloses nothing
            if (taken.size >= MIN_POINTS && count >= MIN_POINTS) Polygon(taken) else null
        }
    }

    /** The dialect these files are written in: `[section]`, `key=value`, `;` comments. */
    private fun ini(text: String): Map<String, Map<String, String>> {
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore(';').substringBefore('#').trim()
            when {
                line.isEmpty() -> {
                    Unit
                }

                line.startsWith("[") && line.endsWith("]") -> {
                    current = sections.getOrPut(line.trim('[', ']').trim().lowercase()) { mutableMapOf() }
                }

                line.contains('=') -> {
                    current?.put(line.substringBefore('=').trim().lowercase(), line.substringAfter('=').trim())
                }

                else -> {
                    Unit
                }
            }
        }
        return sections
    }

    private val SEPARATORS = Regex("[,\\s]+")
    private const val MIN_POINTS = 3
}
