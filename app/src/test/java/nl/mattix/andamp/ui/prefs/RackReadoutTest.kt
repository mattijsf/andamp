// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import nl.mattix.andamp.core.model.ParamSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a control reads as beside its slider.
 *
 * A 0..1 amount reads as a percentage; a control with a range or a unit of its
 * own reads in that unit.
 */
class RackReadoutTest {
    @Test
    fun `an amount reads as a percentage`() {
        val amount = ParamSpec("level", "Level", min = 0f, max = 1f, default = 0.5f)

        assertEquals("50", display(amount, 0.5f))
        assertEquals("100", display(amount, 1f))
    }

    @Test
    fun `a percentage declared as one reads as one, whatever its range`() {
        val declared = ParamSpec("level", "Level", min = 0f, max = 1f, unit = "pc")

        assertEquals("25", display(declared, 0.25f))
    }

    @Test
    fun `a control with a range of its own reads in its own units`() {
        val rate = ParamSpec("rate", "Rate", min = 0.1f, max = 12f, default = 4f, unit = "hz")

        assertEquals("4.0 Hz", display(rate, 4f))
        assertEquals("0.5 Hz", display(rate, 0.5f))
    }

    @Test
    fun `a wide range reads as whole numbers`() {
        // a hundredth of a hertz is not a difference anybody hears at 12 kHz
        val cutoff = ParamSpec("freq", "Frequency", min = 20f, max = 20_000f, default = 1000f, unit = "hz")

        assertEquals("1000 Hz", display(cutoff, 1000f))
    }

    @Test
    fun `a range past one with no unit reads as a plain number`() {
        val gain = ParamSpec("level", "Level", min = 0f, max = 2f, default = 1f)

        assertEquals("1.0", display(gain, 1f))
    }

    @Test
    fun `a declared display scale and unit are used`() {
        // the graph reads a mix as 0..1; a listener reads it as a percentage
        val mix = ParamSpec("mix", "Mix", min = 0f, max = 1f, unit = "pc", displayScale = 100f, displayDecimals = 0)

        // a declared unit is spelled out, unlike the bundled effects' bare
        // percentages
        assertEquals("50 %", display(mix, 0.5f))
        assertEquals("100 %", display(mix, 1f))
    }

    @Test
    fun `decimals are the plug-in's to declare`() {
        val fine = ParamSpec("q", "Q", min = 0.1f, max = 20f, displayDecimals = 2)
        val coarse = ParamSpec("hz", "Hz", min = 0f, max = 20f, unit = "hz", displayDecimals = 0)

        assertEquals("0.71", display(fine, 0.707f))
        assertEquals("6 Hz", display(coarse, 6f))
    }

    @Test
    fun `zero reads as the declared word`() {
        val amount = ParamSpec("bass", "Drum Bass", min = 0f, max = 1f, displayZero = "OFF")

        assertEquals("OFF", display(amount, 0f))
        assertEquals("40", display(amount, 0.4f))
    }

    @Test
    fun `a unit is spelled the way it is written`() {
        assertEquals("6.0 dB", display(ParamSpec("g", "Gain", min = -12f, max = 12f, unit = "db"), 6f))
        assertEquals("20 ms", display(ParamSpec("t", "Time", min = 0f, max = 500f, unit = "ms"), 20f))
    }
}
