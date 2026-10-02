// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.SkinCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Region as AndroidRegion

/**
 * The corner that cuts the windows Winamp's region file does not name.
 *
 * Winamp's region file names four windows, all of them fixed size. A `[Corners]` section
 * declares one corner, and the app mirrors it into four at whatever size the window has,
 * which works for a window that resizes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // paths rasterize through the real Region
@Config(sdk = [35])
class RegionCornerTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        /** A two-pixel chamfer: from the left edge at y=2 round to the top at x=2. */
        const val SQUARE_CORNER = """
            [Corners]
            NumPoints=3
            PointList=0,2, 2,2, 2,0
        """
    }

    private val corner = RegionTxt.parse(SQUARE_CORNER.trimIndent()).corner!!

    /** What the cut window actually covers, in device pixels. */
    private fun covered(
        w: Int,
        h: Int,
        scale: Int = 1,
    ): AndroidRegion {
        val size = Size((w * scale).toFloat(), (h * scale).toFloat())
        val path = RegionShape.cornerPath(corner, size, scale).asAndroidPath()
        return AndroidRegion().apply { setPath(path, AndroidRegion(0, 0, w * scale, h * scale)) }
    }

    @Test
    fun `the declared corner is read off the file`() {
        assertNotNull(corner)
        assertTrue(corner.points.size == 3)
    }

    @Test
    fun `a file that declares none leaves the window a rectangle`() {
        assertNull(RegionTxt.parse("[Normal]\nNumPoints=3\nPointList=0,0, 1,0, 1,1").corner)
        assertNull(RegionShape.corners(null, scale = 1))
    }

    /** One declared corner cuts all four, at whatever size the listener dragged the window to. */
    @Test
    fun `all four corners are cut, at any size`() {
        listOf(275 to 116, 275 to 400, 100 to 60).forEach { (w, h) ->
            val cut = covered(w, h)
            assertTrue("$w x $h keeps its middle", cut.contains(w / 2, h / 2))
            listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1).forEach { (x, y) ->
                assertFalse("$w x $h cuts the corner at $x,$y", cut.contains(x, y))
            }
        }
    }

    /** Only the corner: the edges between them are the window's own. */
    @Test
    fun `the edges between the corners are left alone`() {
        val cut = covered(275, 400)

        assertTrue(cut.contains(137, 0))
        assertTrue(cut.contains(137, 399))
        assertTrue(cut.contains(0, 200))
        assertTrue(cut.contains(274, 200))
    }

    /** The cut is in virtual pixels, so it grows with the window's scale like the art does. */
    @Test
    fun `the corner scales with the window`() {
        val cut = covered(275, 116, scale = 3)

        // three device pixels in, the two-pixel chamfer is still cutting
        assertFalse(cut.contains(3, 3))
        assertTrue(cut.contains(7, 7))
    }

    /**
     * Covers the wiring: a window handed a corner is clipped with it.
     *
     * Asked through the touches, the way [RegionClipTest] asks it: a press goes through a
     * cut-away corner, by the same clip that takes the paint off it.
     */
    @Test
    fun `a window given the corner lets a press through it`() {
        var behind = 0
        val skin =
            SkinLoader
                .loadBase(ApplicationProvider.getApplicationContext<Application>())
                .withRegions(RegionTxt.parse(SQUARE_CORNER.trimIndent()))

        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { behind++ })
                ScaledWindowCanvas(
                    virtualW = 40,
                    virtualH = 40,
                    scale = SCALE,
                    state = WinampState(),
                    widgets = emptyList(),
                    cut = SkinCut(skin),
                ) { drawRect(Color.Red) }
            }
        }

        compose.onRoot().performTouchInput {
            down(Offset(20f * SCALE, 20f * SCALE))
            up()
        }
        compose.waitForIdle()
        assertEquals("a press in the middle stays with the window", 0, behind)

        compose.onRoot().performTouchInput {
            down(Offset(0.5f, 0.5f))
            up()
        }
        compose.waitForIdle()
        assertTrue("a press on the cut-away corner reaches what is behind", behind > 0)
    }

    @Test
    fun `the bundled skin declares a corner`() {
        val skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())

        assertNotNull("the bundled REGION.TXT has a [Corners] section", skin.regions.corner)
        assertNotNull(RegionShape.corners(skin.regions.corner, scale = 1))

        // and the staircase it ships takes the corners off
        val real = skin.regions.corner!!
        val path = RegionShape.cornerPath(real, Size(275f, 400f), scale = 1).asAndroidPath()
        val cut = AndroidRegion().apply { setPath(path, AndroidRegion(0, 0, 275, 400)) }
        assertTrue("the middle is kept", cut.contains(137, 200))
        listOf(0 to 0, 274 to 0, 0 to 399, 274 to 399).forEach { (x, y) ->
            assertFalse("the shipped corner cuts $x,$y", cut.contains(x, y))
        }
    }
}
