// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.core.player.TransportCommand
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap lands on the button it is drawn over. The regions are invisible views
 * over a picture, so these check their scale and their place against the art.
 */
class WidgetButtonsTest {
    @Test
    fun `every button lands inside the picture it is drawn on`() {
        listOf(
            WidgetLayout.choose(MAIN_W, MAIN_H),
            WidgetLayout.choose(MAIN_W * 3, MAIN_H * 3),
            WidgetLayout.choose(MAIN_W * 2, 60),
        ).forEach { layout ->
            WidgetButtons.boxes(layout).forEach { box ->
                assertTrue("${box.button} starts inside the left edge", box.left >= 0)
                assertTrue("${box.button} starts inside the top edge", box.top >= 0)
                assertTrue("${box.button} ends inside the right edge", box.left + box.width <= layout.widthPx)
                assertTrue("${box.button} ends inside the bottom edge", box.top + box.height <= layout.heightPx)
            }
        }
    }

    @Test
    fun `the regions scale as the art does`() {
        // one scale for the whole window, art and touches alike
        val one = WidgetButtons.boxes(WidgetLayout.choose(MAIN_W, MAIN_H)).associateBy { it.button }
        val three = WidgetButtons.boxes(WidgetLayout.choose(MAIN_W * 3, MAIN_H * 3)).associateBy { it.button }

        one.forEach { (button, box) ->
            val bigger = three.getValue(button)
            assertEquals("$button left scales by 3", box.left * 3, bigger.left)
            assertEquals("$button top scales by 3", box.top * 3, bigger.top)
            assertEquals("$button width scales by 3", box.width * 3, bigger.width)
            assertEquals("$button height scales by 3", box.height * 3, bigger.height)
        }
    }

    @Test
    fun `the middle of a button belongs to that button and no other`() {
        // Winamp's transport row is buttons touching edge to edge, and shuffle
        // and repeat share their border column in the sprite sheet: shuffle is
        // 47 wide at 164, so it ends on 210, where repeat begins. The middle of
        // a button still belongs to that button alone
        val boxes = WidgetButtons.boxes(WidgetLayout.choose(MAIN_W, MAIN_H))

        boxes.forEach { box ->
            val x = box.left + box.width / 2
            val y = box.top + box.height / 2
            val hit = boxes.filter { x >= it.left && x < it.left + it.width && y >= it.top && y < it.top + it.height }
            assertEquals("only ${box.button} claims its middle: $hit", listOf(box), hit)
        }
    }

    @Test
    fun `no two buttons share more than the border their art shares`() {
        // one shared column is the sprite sheet's; two would be a wrong coordinate
        val boxes = WidgetButtons.boxes(WidgetLayout.choose(MAIN_W, MAIN_H))

        boxes.forEachIndexed { i, a ->
            boxes.drop(i + 1).forEach { b ->
                val across = minOf(a.left + a.width, b.left + b.width) - maxOf(a.left, b.left)
                val down = minOf(a.top + a.height, b.top + b.height) - maxOf(a.top, b.top)
                val shared = if (across > 0 && down > 0) across else 0
                assertTrue("${a.button} and ${b.button} share at most one column: $shared", shared <= 1)
            }
        }
    }

    @Test
    fun `the shade offers the transport and nothing it has no art for`() {
        val shade = WidgetButtons.boxes(WidgetLayout.choose(MAIN_W * 2, 60)).map { it.button }

        assertEquals(WidgetButton.entries.filter { it.command != null }.toSet(), shade.toSet())
        assertTrue("the shade offers no shuffle", WidgetButton.SHUFFLE !in shade)
    }

    @Test
    fun `the whole window offers every control it draws`() {
        val full = WidgetButtons.boxes(WidgetLayout.choose(MAIN_W, MAIN_H)).map { it.button }

        assertEquals(WidgetButton.entries.toSet(), full.toSet())
    }

    @Test
    fun `each control carries the verb it is, and the flags carry none`() {
        // a button carries the verb; how it is delivered is decided when it
        // is pressed, in WidgetControl
        assertEquals(TransportCommand.PREVIOUS, WidgetButton.PREV.command)
        assertEquals(TransportCommand.PLAY, WidgetButton.PLAY.command)
        assertEquals(TransportCommand.PAUSE, WidgetButton.PAUSE.command)
        assertEquals(TransportCommand.STOP, WidgetButton.STOP.command)
        assertEquals(TransportCommand.NEXT, WidgetButton.NEXT.command)
        assertNull("shuffle carries no transport verb", WidgetButton.SHUFFLE.command)
        assertNull("repeat carries no transport verb", WidgetButton.REPEAT.command)
    }
}
