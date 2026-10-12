// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Test

/** A label in a cell of the library window: drawn as it is where it fits, squeezed where it does not. */
class ListTextSqueezeTest {
    @Test
    fun `a label that fits is not touched`() {
        assertEquals(1f, ListText.squeeze(natural = 40f, room = 50f))
        assertEquals(1f, ListText.squeeze(natural = 50f, room = 50f))
    }

    @Test
    fun `a label wider than its cell is squeezed to the cell`() {
        assertEquals(0.8f, ListText.squeeze(natural = 50f, room = 40f))
    }

    @Test
    fun `nothing to draw, or no room, leaves the scale alone`() {
        assertEquals(1f, ListText.squeeze(natural = 0f, room = 40f))
        assertEquals(1f, ListText.squeeze(natural = 50f, room = 0f))
    }
}
