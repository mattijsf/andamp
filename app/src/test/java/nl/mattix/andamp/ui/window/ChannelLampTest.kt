// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelLampTest {
    @Test
    fun `one channel is mono`() {
        assertEquals(ChannelLamp.MONO, ChannelLamp.of(1, stopped = false))
    }

    @Test
    fun `two channels or more are stereo`() {
        assertEquals(ChannelLamp.STEREO, ChannelLamp.of(2, stopped = false))
        assertEquals(ChannelLamp.STEREO, ChannelLamp.of(6, stopped = false))
    }

    @Test
    fun `an unknown count is stereo`() {
        assertEquals(ChannelLamp.STEREO, ChannelLamp.of(null, stopped = false))
    }

    @Test
    fun `nothing is lit while stopped`() {
        assertEquals(ChannelLamp.NONE, ChannelLamp.of(1, stopped = true))
        assertEquals(ChannelLamp.NONE, ChannelLamp.of(2, stopped = true))
        assertEquals(ChannelLamp.NONE, ChannelLamp.of(null, stopped = true))
    }
}
