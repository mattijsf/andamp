// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.modernskin.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Winamp's host action names, as a skin's buttons send them, sorted by what they ask of the host. */
class HostActionNamesTest {
    @Test
    fun `the menu buttons open the main menu, the skin's at the part it names`() {
        assertEquals(HostAction.Menu("wa5:options"), HostAction.of("MENU", "wa5:options"))
        assertEquals(HostAction.Menu(null), HostAction.of("SYSMENU", null))
        assertEquals(HostAction.Menu(null), HostAction.of("CONTROLMENU", "ignored"))
    }

    @Test
    fun `the visualizer's and the video's full screen are one action`() {
        assertEquals(HostAction.Fullscreen, HostAction.of("VIS_FS", null))
        assertEquals(HostAction.Fullscreen, HostAction.of("VID_FS", null))
    }

    @Test
    fun `show and toggle carry the window, and need one`() {
        assertEquals(HostAction.Show("avs"), HostAction.of("SHOW", "avs"))
        assertEquals(HostAction.Toggle("ml"), HostAction.of("TOGGLE", "ml"))
        assertNull(HostAction.of("SHOW", null))
        assertNull(HostAction.of("TOGGLE", null))
    }

    @Test
    fun `the window buttons and the visualizer's keep their names`() {
        assertEquals(HostAction.Close, HostAction.of("CLOSE", null))
        assertEquals(HostAction.Minimize, HostAction.of("MINIMIZE", null))
        assertEquals(HostAction.VisualizationMenu, HostAction.of("VIS_MENU", null))
        assertEquals(HostAction.NextPreset, HostAction.of("VIS_NEXT", null))
        assertEquals(HostAction.PreviousPreset, HostAction.of("VIS_PREV", null))
        assertEquals(HostAction.TrackInfo, HostAction.of("TRACKINFO", null))
    }

    @Test
    fun `an action the host does not answer is none`() {
        assertNull(HostAction.of("PREFS", null))
        assertNull(HostAction.of("menu", "wa5:options"))
    }
}
