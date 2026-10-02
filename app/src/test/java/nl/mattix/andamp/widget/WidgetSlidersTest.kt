// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.SliderMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget cannot drag, so it covers each slider with tap targets. The art is
 * drawn from the player's own coordinates and the targets are invisible, so
 * these check that the targets sit on their tracks.
 */
class WidgetSlidersTest {
    private val full = WidgetLayout.choose(MAIN_W, MAIN_H)

    private fun seek(layout: WidgetLayout = full) =
        WidgetSliders.steps(layout, seekable = true, volumeControl = true).filter {
            it.slider ==
                WidgetSlider.SEEK
        }

    private fun volume(layout: WidgetLayout = full) =
        WidgetSliders.steps(layout, seekable = true, volumeControl = true).filter { it.slider == WidgetSlider.VOLUME }

    @Test
    fun `the steps cover their track with no gap and no overlap`() {
        // a gap is a press that does nothing where the art says it should work
        listOf(seek(), volume()).forEach { steps ->
            steps.zipWithNext { left, right ->
                assertEquals("the next step starts where this one ends at ${left.fraction}", left.left + left.width, right.left)
            }
        }
    }

    @Test
    fun `the ends of a track are its ends`() {
        assertEquals(0f, seek().first().fraction, 0.06f)
        assertEquals(1f, seek().last().fraction, 0.06f)
        assertEquals(0f, volume().first().fraction, 0.08f)
        assertEquals(1f, volume().last().fraction, 0.08f)
    }

    @Test
    fun `pressing further right always means further along`() {
        listOf(seek(), volume()).forEach { steps ->
            steps.zipWithNext { left, right ->
                assertTrue("fractions rise: ${left.fraction} then ${right.fraction}", right.fraction >= left.fraction)
            }
        }
    }

    @Test
    fun `a press means what dragging to the same place means`() {
        // the widget and the player use the same arithmetic, so the thumb
        // lands under the finger in both
        seek().forEach { step ->
            val middle = (step.left + step.width / 2f)
            val dragged = SliderMath.horizontalFraction(middle, Dest.POSBAR.x, POSBAR_THUMB, POSBAR_TRAVEL)
            assertEquals("a press at $middle means the dragged fraction", dragged, step.fraction, 1e-4f)
        }
    }

    @Test
    fun `a track that cannot be moved is not covered in targets`() {
        // a stream has no position to seek to
        val steps = WidgetSliders.steps(full, seekable = false, volumeControl = true)

        assertTrue("an unseekable track has no seek targets", steps.none { it.slider == WidgetSlider.SEEK })
        assertTrue("the volume keeps its targets", steps.any { it.slider == WidgetSlider.VOLUME })
    }

    @Test
    fun `the shade offers no sliders at all`() {
        // the shade is fourteen pixels tall, too little to press a slider
        val shade = WidgetLayout.choose(MAIN_W * 2, 60)

        assertTrue(shade.shaded)
        assertTrue(WidgetSliders.steps(shade, seekable = true, volumeControl = true).isEmpty())
    }

    @Test
    fun `the targets scale with the art they sit on`() {
        val bigger = seek(WidgetLayout.choose(MAIN_W * 3, MAIN_H * 3))

        seek().zip(bigger) { one, three ->
            assertEquals(one.left * 3, three.left)
            assertEquals(one.width * 3, three.width)
            assertEquals("the value is the same at any size", one.fraction, three.fraction, 1e-6f)
        }
    }

    @Test
    fun `every target stays inside the picture`() {
        val layout = WidgetLayout.choose(MAIN_W * 2, MAIN_H * 2)

        WidgetSliders.steps(layout, seekable = true, volumeControl = true).forEach {
            assertTrue("the target ends inside the right edge", it.left + it.width <= layout.widthPx)
            assertTrue("the target ends inside the bottom edge", it.top + it.height <= layout.heightPx)
        }
    }

    private companion object {
        const val POSBAR_THUMB = 29
        const val POSBAR_TRAVEL = 248 - 29
    }

    @Test
    fun `with the volume control off there are no volume targets`() {
        val off = WidgetSliders.steps(full, seekable = true, volumeControl = false)

        assertTrue("no volume targets with the control off", off.none { it.slider == WidgetSlider.VOLUME })
        assertTrue("the seek bar keeps its targets", off.any { it.slider == WidgetSlider.SEEK })
    }
}
