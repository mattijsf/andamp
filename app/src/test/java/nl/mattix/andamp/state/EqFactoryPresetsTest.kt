// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqFactoryPresetsTest {
    @Test
    fun `there are 17 factory presets`() {
        assertEquals(17, EqFactoryPresets.ALL.size)
    }

    @Test
    fun `every preset has 10 bands and all values on the 0-63 slider scale`() {
        for (preset in EqFactoryPresets.ALL) {
            assertEquals(preset.name, 10, preset.bands.size)
            assertTrue(preset.name, preset.preamp in 0..63)
            assertTrue(preset.name, preset.bands.all { it in 0..63 })
        }
    }

    @Test
    fun `rock matches webamp builtin json minus one`() {
        // builtin.json: hz60=45 .. hz16000=50, preamp=33 (1..64 scale)
        val rock = EqFactoryPresets.ALL.first { it.name == "Rock" }
        assertEquals(listOf(44, 39, 22, 18, 25, 38, 46, 49, 49, 49), rock.bands)
        assertEquals(32, rock.preamp)
    }

    @Test
    fun `classical's first band is 32`() {
        val classical = EqFactoryPresets.ALL.first { it.name == "Classical" }
        assertEquals(32, classical.bands[0])
    }

    @Test
    fun `names are unique`() {
        val names = EqFactoryPresets.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
