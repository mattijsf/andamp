// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A drag that is reported by its distance alone moves the slider the last tap landed on, as
 * the touches of a finger that comes down on the slider's thumb.
 */
class HeldSliderTest {
    private val touches = mutableListOf<String>()
    private val held = HeldSlider { what, x, y -> touches += "$what ${x.toInt()},${y.toInt()}" }

    /** A slider 100 to 300 across and 40 to 60 down, with its thumb at [thumbX]. */
    private fun slider(
        thumbX: Float = 150f,
        id: String = "volume",
        covers: IntRect = IntRect(100, 40, 300, 60),
    ) = SurfaceSlider(id, covers) { Offset(thumbX, 50f) }

    /** A surface whose one slider is [there]. */
    private fun with(there: SurfaceSlider?): (Float, Float) -> SurfaceSlider? = { _, _ -> there }

    @Test
    fun `a drag with no slider tapped moves nothing and is not taken`() {
        assertFalse(held.dragBy(10f, 0f, with(slider())))

        assertTrue(touches.isEmpty())
    }

    @Test
    fun `a drag after a tap on a slider comes down on its thumb and moves on from there`() {
        val volume = slider(thumbX = 150f)
        held.tapped(volume)

        assertTrue(held.dragBy(20f, 0f, with(volume)))

        assertEquals(listOf("DOWN 150,50", "MOVE 170,50"), touches)
    }

    @Test
    fun `the finger stays down from one move of a drag to the next`() {
        val volume = slider()
        held.tapped(volume)

        held.dragBy(20f, 0f, with(volume))
        held.dragBy(-5f, 0f, with(volume))

        assertEquals(listOf("DOWN 150,50", "MOVE 170,50", "MOVE 165,50"), touches)
        assertTrue(held.down)
    }

    @Test
    fun `the finger is lifted once, where it was`() {
        val volume = slider()
        held.tapped(volume)
        held.dragBy(20f, 0f, with(volume))

        held.letGo()
        held.letGo()

        assertEquals("UP 170,50", touches.last())
        assertEquals(1, touches.count { it.startsWith("UP") })
        assertFalse(held.down)
    }

    /** The song moved the thumb on, or another hand did: the finger comes down where it is now. */
    @Test
    fun `the next drag comes down where the thumb is by then`() {
        held.tapped(slider(thumbX = 150f))
        held.dragBy(20f, 0f, with(slider(thumbX = 150f)))
        held.letGo()
        touches.clear()

        held.dragBy(10f, 0f, with(slider(thumbX = 220f)))

        assertEquals(listOf("DOWN 220,50", "MOVE 230,50"), touches)
    }

    @Test
    fun `the finger does not leave the slider`() {
        val volume = slider(thumbX = 290f)
        held.tapped(volume)

        held.dragBy(500f, 80f, with(volume))

        assertEquals("MOVE 299,59", touches.last())
    }

    @Test
    fun `a tap on something else lets the slider go`() {
        val volume = slider()
        held.tapped(volume)
        held.tapped(null)

        assertFalse(held.dragBy(20f, 0f, with(volume)))
        assertTrue(touches.isEmpty())
    }

    @Test
    fun `a tap lifts a finger that is still down`() {
        val volume = slider()
        held.tapped(volume)
        held.dragBy(20f, 0f, with(volume))

        held.tapped(null)

        assertEquals("UP 170,50", touches.last())
    }

    /** Another window came up, or the window moved on the surface, without a tap. */
    @Test
    fun `a slider that is no longer where it was is let go`() {
        held.tapped(slider())

        assertFalse("nothing is there now", held.dragBy(20f, 0f, with(null)))
        assertFalse("and it stays let go", held.dragBy(20f, 0f, with(slider())))
        assertTrue(touches.isEmpty())
    }

    @Test
    fun `another slider in the same place is not moved in its stead`() {
        held.tapped(slider(id = "volume"))

        assertFalse(held.dragBy(20f, 0f, with(slider(id = "eq.band3"))))
    }

    @Test
    fun `the same slider somewhere else is not moved from here`() {
        held.tapped(slider())

        assertFalse(held.dragBy(20f, 0f, with(slider(covers = IntRect(50, 20, 150, 30)))))
    }
}
