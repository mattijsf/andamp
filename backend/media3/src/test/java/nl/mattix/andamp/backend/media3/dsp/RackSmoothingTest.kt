// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the audio thread hears while a control travels.
 *
 * A number glides, because a step in a gain or a filter corner is a click. A
 * choice does not: the values between two options are other options.
 */
class RackSmoothingTest {
    private val rate = 44_100

    private fun smoothing() = RackSmoothing().apply { configure(rate, BuiltInEffects.all) }

    private fun rack(
        mode: Float = 0f,
        depth: Float = 0.5f,
    ) = RackSettings(
        BuiltInEffects.all.map { spec ->
            val values =
                when (spec.id) {
                    BuiltInEffects.MODULATION -> spec.defaults.with("mode", mode).with("depth", depth)
                    else -> spec.defaults
                }
            RackSlot(spec.id, enabled = spec.id == BuiltInEffects.MODULATION, params = values)
        },
    )

    private fun RackSmoothing.modulation(id: String) = current[BuiltInEffects.MODULATION]!!.params[id, -1f]

    @Test
    fun `a choice arrives at the option that was picked, not at the ones in between`() {
        // phaser is 2 and chorus is 0, so a walk between them would read as
        // flanger while it passes 1
        val smoothing = smoothing()
        smoothing.aim(rack(mode = 2f))
        smoothing.settle()

        smoothing.aim(rack(mode = 0f))

        repeat(TICKS) {
            smoothing.tick()
            val mode = smoothing.modulation("mode")
            assertTrue("a mode change passes through no other option: $mode", mode == 0f || mode == 2f)
        }
        assertEquals(0f, smoothing.modulation("mode"), 0f)
    }

    @Test
    fun `a choice arrives on the first tick`() {
        val smoothing = smoothing()
        smoothing.aim(rack(mode = 0f))
        smoothing.settle()

        smoothing.aim(rack(mode = 2f))
        smoothing.tick()

        assertEquals("the mode arrives on the first tick", 2f, smoothing.modulation("mode"), 0f)
    }

    @Test
    fun `a number moves a step at a time`() {
        // a rack that jumped every value would click
        val smoothing = smoothing()
        smoothing.aim(rack(depth = 0f))
        smoothing.settle()

        smoothing.aim(rack(depth = 1f))
        smoothing.tick()

        val after = smoothing.modulation("depth")
        assertTrue("depth glides: $after after one tick", after > 0f && after < 0.2f)
    }

    @Test
    fun `a number lands where the slider is`() {
        val smoothing = smoothing()
        smoothing.aim(rack(depth = 0f))
        smoothing.settle()

        smoothing.aim(rack(depth = 1f))
        repeat(TICKS) { smoothing.tick() }

        assertEquals(1f, smoothing.modulation("depth"), 0f)
    }

    private companion object {
        /** Long enough for the 20 ms walk to land: it settles in about 187. */
        const val TICKS = 400
    }
}
