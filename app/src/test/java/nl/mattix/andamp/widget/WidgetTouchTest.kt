// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every place on the widget a finger can land, taken together.
 *
 * The targets are invisible and larger than the art they cover, because the
 * art is far below a touch target's size. These check that no target reaches
 * into a neighbor and that each is still bigger than what it covers.
 */
class WidgetTouchTest {
    private data class Region(
        val name: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private fun regions(layout: WidgetLayout): List<Region> {
        val buttons =
            WidgetButtons.boxes(layout).map {
                Region(it.button.name, it.left, it.top, it.left + it.width, it.top + it.height)
            }
        val sliders =
            WidgetSliders.steps(layout, seekable = true, volumeControl = true).mapIndexed { at, step ->
                Region("${step.slider}#$at", step.left, step.top, step.left + step.width, step.top + step.height)
            }
        return buttons + sliders
    }

    private fun overlap(
        a: Region,
        b: Region,
    ): Int {
        val across = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val down = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (across > 0 && down > 0) across else 0
    }

    @Test
    fun `no control reaches into another`() {
        val all = regions(WidgetLayout.choose(MAIN_W, MAIN_H))

        all.forEachIndexed { i, a ->
            all.drop(i + 1).forEach { b ->
                // shuffle and repeat share their border column in the sprite
                // sheet; anything wider is a band that has grown into its neighbor
                val shared = overlap(a, b)
                val sameSlider = a.name.substringBefore('#') == b.name.substringBefore('#')
                if (!sameSlider) assertTrue("${a.name} and ${b.name} share at most one column: $shared", shared <= 1)
            }
        }
    }

    @Test
    fun `the bands are all taller than the art they cover`() {
        // a band no taller than its sprite is hard to hit
        val all = regions(WidgetLayout.choose(MAIN_W, MAIN_H)).associateBy { it.name }

        assertTrue("the transport row is taller than its art", all.getValue("PLAY").let { it.bottom - it.top } > TRANSPORT_ART_H)
        assertTrue(
            "the seek bar is taller than its art",
            all.values.first { it.name.startsWith("SEEK") }.let { it.bottom - it.top } > POSBAR_ART_H,
        )
        assertTrue(
            "the volume is taller than its art",
            all.values.first { it.name.startsWith("VOLUME") }.let { it.bottom - it.top } > VOLUME_ART_H,
        )
    }

    @Test
    fun `nothing reaches outside the window`() {
        listOf(WidgetLayout.choose(MAIN_W, MAIN_H), WidgetLayout.choose(MAIN_W * 3, MAIN_H * 3)).forEach { layout ->
            regions(layout).forEach {
                assertTrue("${it.name} starts inside the top edge", it.top >= 0)
                assertTrue("${it.name} ends inside the bottom edge", it.bottom <= layout.heightPx)
                assertTrue("${it.name} ends inside the right edge", it.right <= layout.widthPx)
            }
        }
    }

    @Test
    fun `the shade's buttons take the whole of its height`() {
        // the shade is fourteen pixels tall, so its buttons take all of it
        val shade = WidgetLayout.choose(MAIN_W * 2, 60)

        assertTrue(shade.shaded)
        WidgetButtons.boxes(shade).forEach {
            assertTrue("${it.button} fills the shade's height", it.height == shade.heightPx)
        }
    }

    private companion object {
        const val TRANSPORT_ART_H = 18
        const val POSBAR_ART_H = 10
        const val VOLUME_ART_H = 13
    }
}
