// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EqPresetCodecTest {
    @Test
    fun `encodes and decodes a preset round-trip`() {
        val preset = EqPreset("Rock", 32, listOf(44, 39, 22, 18, 25, 38, 46, 49, 49, 49))
        assertEquals(preset, EqPresetCodec.decode(EqPresetCodec.encode(preset)))
    }

    @Test
    fun `name may contain the field separator`() {
        val preset = EqPreset("My|weird|name", 10, List(10) { it })
        assertEquals(preset, EqPresetCodec.decode(EqPresetCodec.encode(preset)))
    }

    @Test
    fun `name may contain commas and unicode`() {
        val preset = EqPreset("Bas, méér & 🎵", 63, List(10) { 63 - it })
        assertEquals(preset, EqPresetCodec.decode(EqPresetCodec.encode(preset)))
    }

    @Test
    fun `malformed input decodes to null`() {
        assertNull(EqPresetCodec.decode(""))
        assertNull(EqPresetCodec.decode("no separators here"))
        assertNull(EqPresetCodec.decode("x|1,2,3,4,5,6,7,8,9,10|bad preamp"))
        assertNull(EqPresetCodec.decode("31|1,2,3|too few bands"))
        assertNull(EqPresetCodec.decode("31|1,2,3,4,5,x,7,8,9,10|bad band"))
        assertNull(EqPresetCodec.decode("31|1,2,3,4,5,6,7,8,9,10|"))
    }
}
