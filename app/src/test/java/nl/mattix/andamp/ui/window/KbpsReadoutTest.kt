// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KbpsReadout]: the bitrate in a field of three characters. */
class KbpsReadoutTest {
    @Test
    fun `a bitrate of up to three digits is shown as it is`() {
        assertEquals("8", KbpsReadout.of(8))
        assertEquals("96", KbpsReadout.of(96))
        assertEquals("128", KbpsReadout.of(128))
        assertEquals("999", KbpsReadout.of(999))
    }

    @Test
    fun `from 1000 the bitrate is counted in hundreds`() {
        assertEquals("10H", KbpsReadout.of(1_000))
        assertEquals("10H", KbpsReadout.of(1_088))
        assertEquals("14H", KbpsReadout.of(1_411))
        assertEquals("99H", KbpsReadout.of(9_999))
    }

    @Test
    fun `the hundreds are cut, not rounded`() {
        assertEquals("10H", KbpsReadout.of(1_099))
    }

    @Test
    fun `from 10000 the bitrate is counted in ten-thousands`() {
        assertEquals("1C", KbpsReadout.of(10_000))
        assertEquals("1C", KbpsReadout.of(12_000))
        assertEquals("28C", KbpsReadout.of(282_240))
    }

    @Test
    fun `no bitrate is wider than the field`() {
        listOf(0, 9, 99, 999, 1_000, 9_999, 10_000, 99_999, 100_000, 999_999, 5_644_800).forEach { kbps ->
            assertTrue("$kbps reads ${KbpsReadout.of(kbps)}", KbpsReadout.of(kbps).length <= 3)
        }
    }
}
