// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REGION.TXT, in the shapes skins ship it.
 *
 * The tolerances follow webamp's parser: points separated by commas or spaces,
 * more polygons named than there are points for, and polygons too small to
 * enclose anything.
 */
class RegionTxtTest {
    @Test
    fun `a plain region gives one polygon in window coordinates`() {
        val regions =
            RegionTxt.parse(
                """
                [Normal]
                NumPoints=4
                PointList=0,0,275,0,275,116,0,116
                """.trimIndent(),
            )

        val polygon = regions[RegionTxt.Window.MAIN]!!.single()
        assertEquals(4, polygon.points.size)
        assertEquals(RegionTxt.Point(0, 0), polygon.points.first())
        assertEquals(RegionTxt.Point(0, 116), polygon.points.last())
    }

    @Test
    fun `points may be separated by spaces instead of commas`() {
        val regions =
            RegionTxt.parse(
                """
                [Normal]
                NumPoints=3
                PointList=0 0 10 0 10 10
                """.trimIndent(),
            )

        assertEquals(3, regions[RegionTxt.Window.MAIN]!!.single().points.size)
    }

    @Test
    fun `several polygons split the point list by their own counts`() {
        val regions =
            RegionTxt.parse(
                """
                [Normal]
                NumPoints=3,4
                PointList=0,0,10,0,10,10,20,20,30,20,30,30,20,30
                """.trimIndent(),
            )

        val polygons = regions[RegionTxt.Window.MAIN]!!
        assertEquals(2, polygons.size)
        assertEquals(3, polygons[0].points.size)
        assertEquals(RegionTxt.Point(20, 20), polygons[1].points.first())
    }

    @Test
    fun `a polygon of fewer than three points encloses nothing and is dropped`() {
        val regions =
            RegionTxt.parse(
                """
                [Normal]
                NumPoints=2,3
                PointList=0,0,10,0,20,20,30,20,30,30
                """.trimIndent(),
            )

        val polygons = regions[RegionTxt.Window.MAIN]!!
        assertEquals(1, polygons.size)
        assertEquals(RegionTxt.Point(20, 20), polygons.single().points.first())
    }

    @Test
    fun `a skin naming more polygons than it gave points for keeps what it has`() {
        val regions =
            RegionTxt.parse(
                """
                [Normal]
                NumPoints=3,4
                PointList=0,0,10,0,10,10
                """.trimIndent(),
            )

        assertEquals(1, regions[RegionTxt.Window.MAIN]!!.size)
    }

    @Test
    fun `every window a region file may name is read`() {
        val regions =
            RegionTxt.parse(
                """
                [Normal]
                NumPoints=3
                PointList=0,0,10,0,10,10
                [WindowShade]
                NumPoints=3
                PointList=0,0,20,0,20,10
                [Equalizer]
                NumPoints=3
                PointList=0,0,30,0,30,10
                [EqualizerWS]
                NumPoints=3
                PointList=0,0,40,0,40,10
                """.trimIndent(),
            )

        assertEquals(4, regions.byWindow.size)
        assertEquals(40, regions[RegionTxt.Window.EQ_SHADE]!!.single().points[1].x)
    }

    @Test
    fun `section names are read however they are cased`() {
        val regions =
            RegionTxt.parse(
                """
                [NORMAL]
                numpoints=3
                POINTLIST=0,0,10,0,10,10
                """.trimIndent(),
            )

        assertEquals(1, regions[RegionTxt.Window.MAIN]!!.size)
    }

    @Test
    fun `a section without points is no region at all`() {
        val regions = RegionTxt.parse("[Normal]\nNumPoints=4")

        assertNull(regions[RegionTxt.Window.MAIN])
        assertTrue(regions.isEmpty)
    }

    @Test
    fun `no file means no region`() {
        assertTrue(RegionTxt.parse(null).isEmpty)
        assertTrue(RegionTxt.parse("   ").isEmpty)
    }

    @Test
    fun `comments and stray lines are ignored`() {
        val regions =
            RegionTxt.parse(
                """
                ; the shape of the player
                [Normal]
                NumPoints=3   ; three corners
                PointList=0,0,10,0,10,10
                something without an equals sign
                """.trimIndent(),
            )

        assertEquals(3, regions[RegionTxt.Window.MAIN]!!.single().points.size)
    }
}
