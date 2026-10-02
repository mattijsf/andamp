// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.ParamSpec
import nl.mattix.andamp.core.model.ParamValues
import nl.mattix.andamp.core.model.Preset
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status line of a closed card. */
class RackDigestTest {
    private val spec =
        EffectSpec(
            id = "x",
            name = "X",
            params =
                listOf(
                    ParamSpec("mix", "Mix", default = 0.5f),
                    ParamSpec("room", "Room", default = 0.5f),
                    ParamSpec("air", "Air", default = 0.5f),
                    ParamSpec("mode", "Mode", default = 0f, choices = mapOf("A" to 0f, "B" to 1f)),
                ),
            presets = listOf(Preset("Warm", mapOf("mix" to 0.8f, "room" to 0.6f))),
        )

    private fun slot(vararg values: Pair<String, Float>) = RackSlot(spec.id, enabled = true, params = ParamValues(values.toMap()))

    @Test
    fun `untouched is default settings`() {
        assertEquals("Default settings", RackDigest.statusOf(spec, slot()))
    }

    @Test
    fun `a matching preset is named`() {
        assertEquals("Warm", RackDigest.statusOf(spec, slot("mix" to 0.8f, "room" to 0.6f)))
    }

    @Test
    fun `a preset ignores the controls it never set`() {
        // Warm does not name Air, so moving Air leaves the status as Warm
        assertEquals("Warm", RackDigest.statusOf(spec, slot("mix" to 0.8f, "room" to 0.6f, "air" to 0.9f)))
    }

    @Test
    fun `moved controls are named with their values`() {
        assertEquals("Mix 80", RackDigest.statusOf(spec, slot("mix" to 0.8f)))
    }

    @Test
    fun `more than two moved controls end in an ellipsis`() {
        val line = RackDigest.statusOf(spec, slot("mix" to 0.8f, "room" to 0.9f, "air" to 0.1f))

        assertTrue(line, line.endsWith("…"))
        assertTrue(line, "Mix 80" in line && "Room 90" in line)
        assertFalse("the third stays behind the ellipsis", "Air" in line)
    }

    @Test
    fun `a choice does not count as a moved control`() {
        // its value is a setting, not a quantity
        assertEquals("Default settings", RackDigest.statusOf(spec, slot("mode" to 0f)))
    }
}
