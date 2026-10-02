// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The readout the marquee shows while a slider is held. */
class MarqueeOverrideTest {
    private val state = WinampState()

    @Test
    fun `with nothing held there is no readout`() {
        assertNull(state.marqueeOverride)
    }

    @Test
    fun `a held band reads out its frequency and gain`() {
        state.eqBands[6] = EqOps.CENTER
        state.pressedWidget = "eq.band6"
        assertEquals("EQ: 6KHZ: +0.0 DB", state.marqueeOverride)
    }

    @Test
    fun `band labels match the ones printed under the sliders`() {
        state.pressedWidget = "eq.band0"
        assertEquals("EQ: 60HZ: +0.0 DB", state.marqueeOverride)
        state.pressedWidget = "eq.band3"
        assertEquals("EQ: 600HZ: +0.0 DB", state.marqueeOverride)
        state.pressedWidget = "eq.band9"
        assertEquals("EQ: 16KHZ: +0.0 DB", state.marqueeOverride)
    }

    @Test
    fun `the extremes read out the full range this app applies`() {
        state.pressedWidget = "eq.band0"
        // the range is +/-20 dB with 0 dB at slider 32, so the top of a 0..63
        // slider reads +19.4
        state.eqBands[0] = 63
        assertEquals("EQ: 60HZ: +19.4 DB", state.marqueeOverride)
        state.eqBands[0] = 0
        assertEquals("EQ: 60HZ: -20.0 DB", state.marqueeOverride)
    }

    @Test
    fun `the preamp has its own readout`() {
        state.pressedWidget = "eq.preamp"
        state.preamp = 63
        assertEquals("EQ: PREAMP: +19.4 DB", state.marqueeOverride)
    }

    @Test
    fun `volume reads out as a percentage`() {
        state.pressedWidget = "main.volume"
        state.volume = 78
        assertEquals("VOLUME: 78%", state.marqueeOverride)
    }

    @Test
    fun `balance names its side, and says CENTER at rest`() {
        state.pressedWidget = "main.balance"
        state.balance = 0
        assertEquals("BALANCE: CENTER", state.marqueeOverride)
        state.balance = -20
        assertEquals("BALANCE: 20% LEFT", state.marqueeOverride)
        state.balance = 35
        assertEquals("BALANCE: 35% RIGHT", state.marqueeOverride)
    }

    @Test
    fun `widgets without a readout never hijack the marquee`() {
        state.pressedWidget = "eq.presets"
        assertNull(state.marqueeOverride)
        state.pressedWidget = "main.play"
        assertNull(state.marqueeOverride)
    }

    @Test
    fun `the readout follows the band during a sweep`() {
        state.eqBands[1] = 50
        state.pressedWidget = "eq.band1"
        val first = state.marqueeOverride
        state.pressedWidget = "eq.band2"
        assertEquals(true, first != state.marqueeOverride)
        assertEquals("EQ: 310HZ: +0.0 DB", state.marqueeOverride)
    }
}
