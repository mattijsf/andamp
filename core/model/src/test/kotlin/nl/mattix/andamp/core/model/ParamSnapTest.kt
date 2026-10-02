// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** A slider's snap points: a value within reach lands on one, and every other value passes through. */
class ParamSnapTest {
    private val centre = ParamSpec("pan", "Pan", default = 0.5f, snap = listOf(0.5f))

    @Test
    fun `a slider with no snap points passes every value through`() {
        val free = ParamSpec("free", "Free")

        listOf(0f, 0.017f, 0.5f, 0.999f, 1f).forEach { assertEquals(it, free.snapped(it), 0f) }
    }

    @Test
    fun `close to a snap point lands on it`() {
        assertEquals(0.5f, centre.snapped(0.52f), 0f)
        assertEquals(0.5f, centre.snapped(0.48f), 0f)
        assertEquals(0.5f, centre.snapped(0.5f), 0f)
    }

    @Test
    fun `past the detent the value is left alone`() {
        assertEquals(0.56f, centre.snapped(0.56f), 0f)
        assertEquals(0.44f, centre.snapped(0.44f), 0f)
        assertEquals(0f, centre.snapped(0f), 0f)
        assertEquals(1f, centre.snapped(1f), 0f)
    }

    @Test
    fun `the detent is a fraction of the travel`() {
        val wide = ParamSpec("freq", "Freq", min = 0f, max = 1000f, snap = listOf(500f))

        assertEquals("520 is within 3% of the travel from 500", 500f, wide.snapped(520f), 0f)
        assertEquals(560f, wide.snapped(560f), 0f)
    }

    @Test
    fun `with several points a value takes the nearest one`() {
        val thirds = ParamSpec("x", "X", snap = listOf(0f, 0.5f, 1f))

        assertEquals(0f, thirds.snapped(0.02f), 0f)
        assertEquals(0.5f, thirds.snapped(0.49f), 0f)
        assertEquals(1f, thirds.snapped(0.98f), 0f)
        assertEquals(0.25f, thirds.snapped(0.25f), 0f)
    }

    @Test
    fun `the detent reaches three percent of the travel`() {
        // checked just inside and just outside the boundary, so a changed reach fails here
        assertEquals("a value just inside the detent snaps to the center", 0.5f, centre.snapped(0.5f + 0.0299f), 0f)
        assertEquals("a value just outside the detent is left alone", 0.5f + 0.0301f, centre.snapped(0.5f + 0.0301f), 0f)
        assertEquals("a value just inside the detent below the center snaps to it", 0.5f, centre.snapped(0.5f - 0.0299f), 0f)
        assertEquals(
            "a value just outside the detent below the center is left alone",
            0.5f - 0.0301f,
            centre.snapped(0.5f - 0.0301f),
            0f,
        )
    }

    @Test
    fun `every built-in snap point is at the center`() {
        val declared =
            BuiltInEffects.all.flatMap { spec -> spec.params.filter { it.snap.isNotEmpty() }.map { spec.id to it } }

        assertEquals("only the modulation's stereo parameter declares a snap point", 1, declared.size)
        declared.forEach { (effect, param) ->
            assertEquals("$effect.${param.id}", listOf(0.5f), param.snap)
            assertEquals("$effect.${param.id} snaps a value just past the center", 0.5f, param.snapped(0.515f), 0f)
        }
    }
}
