// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

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
        assertEquals("a classic skin's Winamp logo", HostAction.About, HostAction.of("ABOUT", null))
        assertEquals("a classic skin's clutter bar A", HostAction.AlwaysOnTop, HostAction.of("ALWAYS_ON_TOP", null))
    }

    @Test
    fun `a classic skin's clutter bar reads Always On Top, and Double Size as the skin filling the screen`() {
        var onTop = true
        var filled = false
        val open = WalPrepare.windowOpen(alwaysOnTop = { onTop }, filled = { filled })
        assertEquals(true, open("ontop"))
        onTop = false
        assertEquals(false, open("ontop"))
        assertEquals(false, open("doublesize"))
        filled = true
        assertEquals(true, open("doublesize"))
        assertEquals("the clutter bar's D", HostAction.DoubleSize, HostAction.of("DOUBLESIZE", null))
        assertEquals("the equalizer's PRESETS", HostAction.EqPresets, HostAction.of("EQ_PRESETS", null))
    }

    @Test
    fun `a classic playlist's picks carry the pick, and the selection its row or none`() {
        assertEquals(HostAction.Playlist("sort"), HostAction.of("PLAYLIST", "sort"))
        assertNull("a pick needs its name", HostAction.of("PLAYLIST", null))
        assertEquals(HostAction.PlaylistSelect(3), HostAction.of("PLAYLIST_SELECT", "3"))
        assertEquals(HostAction.PlaylistSelect(null), HostAction.of("PLAYLIST_SELECT", null))
        assertEquals("a held row asks for its menu", HostAction.PlaylistMenu(4), HostAction.of("PLAYLIST_MENU", "4"))
        assertNull("a row menu needs its row", HostAction.of("PLAYLIST_MENU", null))
        assertEquals(HostAction.PlaylistSelection(listOf(0, 2, 3)), HostAction.of("PLAYLIST_SELECTION", "0,2,3"))
        assertEquals("none selected", HostAction.PlaylistSelection(emptyList()), HostAction.of("PLAYLIST_SELECTION", null))
    }

    @Test
    fun `an action the host does not answer is none`() {
        assertNull(HostAction.of("PREFS", null))
        assertNull(HostAction.of("menu", "wa5:options"))
    }
}
