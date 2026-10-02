// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceVolumeTest {
    @Test
    fun `the ends of the slider are the ends of the device's range`() {
        assertEquals(0, DeviceVolume.stepFor(0f, min = 0, max = 15))
        assertEquals(15, DeviceVolume.stepFor(1f, min = 0, max = 15))
        assertEquals(0f, DeviceVolume.fractionFor(0, min = 0, max = 15))
        assertEquals(1f, DeviceVolume.fractionFor(15, min = 0, max = 15))
    }

    @Test
    fun `a fraction lands on the nearest step`() {
        assertEquals(8, DeviceVolume.stepFor(0.5f, min = 0, max = 15))
        assertEquals(5, DeviceVolume.stepFor(0.34f, min = 0, max = 15))
    }

    @Test
    fun `a device whose volume does not start at zero keeps its floor`() {
        assertEquals(3, DeviceVolume.stepFor(0f, min = 3, max = 11))
        assertEquals(7, DeviceVolume.stepFor(0.5f, min = 3, max = 11))
        assertEquals(0f, DeviceVolume.fractionFor(3, min = 3, max = 11))
    }

    @Test
    fun `a fraction outside the slider is pulled back onto it`() {
        assertEquals(0, DeviceVolume.stepFor(-1f, min = 0, max = 15))
        assertEquals(15, DeviceVolume.stepFor(2f, min = 0, max = 15))
        assertEquals(1f, DeviceVolume.fractionFor(99, min = 0, max = 15))
        assertEquals(0f, DeviceVolume.fractionFor(-99, min = 0, max = 15))
    }

    @Test
    fun `a device with no range to speak of does not divide by zero`() {
        assertEquals(5, DeviceVolume.stepFor(0.5f, min = 5, max = 5))
        assertEquals(0f, DeviceVolume.fractionFor(5, min = 5, max = 5))
    }

    @Test
    fun `every step maps back to a fraction that returns to it`() {
        (0..15).forEach { step ->
            val fraction = DeviceVolume.fractionFor(step, min = 0, max = 15)
            assertEquals(step, DeviceVolume.stepFor(fraction, min = 0, max = 15))
        }
    }
}
