// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.visualizer.core.PresetGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [PresetNotice.textFor]: the one line the plug-in window shows for a preset that is not whole. */
class PresetNoticeTest {
    @Test
    fun `a preset that plays whole says nothing`() {
        assertNull(PresetNotice.textFor(PresetGaps.NONE, EnglishWords))
    }

    @Test
    fun `the effects left out are named`() {
        assertEquals(
            "Not shown in this preset: Text, Picture",
            PresetNotice.textFor(PresetGaps(missing = listOf("Text", "Picture")), EnglishWords),
        )
    }

    @Test
    fun `past three, the rest are counted`() {
        val gaps = PresetGaps(missing = listOf("Text", "Picture", "AVI", "SVP", "Picture II"))

        assertEquals("Not shown in this preset: Text, Picture, AVI and 2 more", PresetNotice.textFor(gaps, EnglishWords))
    }

    @Test
    fun `a plug-in is named without its author`() {
        val gaps = PresetGaps(missing = listOf("Acko.net: Texer II", "Jheriko : MULTIFILTER", "FunkyFX FyrewurX v1"))

        assertEquals(
            "Not shown in this preset: Texer II, MULTIFILTER, FunkyFX FyrewurX v1",
            PresetNotice.textFor(gaps, EnglishWords),
        )
    }

    @Test
    fun `two plug-ins that come down to one name are named once`() {
        val gaps = PresetGaps(missing = listOf("Acko.net: Texer II", "Texer II"))

        assertEquals("Not shown in this preset: Texer II", PresetNotice.textFor(gaps, EnglishWords))
    }

    @Test
    fun `a preset that could not be read says so, whatever else is listed`() {
        assertEquals("This preset could not be read", PresetNotice.textFor(PresetGaps(unreadable = true), EnglishWords))
        assertEquals(
            "This preset could not be read",
            PresetNotice.textFor(PresetGaps(unreadable = true, missing = listOf("Text")), EnglishWords),
        )
    }
}
