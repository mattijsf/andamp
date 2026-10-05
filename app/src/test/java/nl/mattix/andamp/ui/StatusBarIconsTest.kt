// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status bar's icons are dark over something light, whatever it is that is under the bar. */
class StatusBarIconsTest {
    @Test
    fun `over the floating player the wallpaper decides`() {
        assertTrue(statusBarIconsDark(onPlayer = true, playerFillsScreen = false, lightWallpaper = true, lightScreen = false))
        assertFalse(statusBarIconsDark(onPlayer = true, playerFillsScreen = false, lightWallpaper = false, lightScreen = true))
    }

    @Test
    fun `a player that fills the screen has black under the bar, whatever the wallpaper`() {
        assertFalse(statusBarIconsDark(onPlayer = true, playerFillsScreen = true, lightWallpaper = true, lightScreen = true))
    }

    @Test
    fun `any other screen decides for itself, whatever the player is set to`() {
        assertTrue(statusBarIconsDark(onPlayer = false, playerFillsScreen = true, lightWallpaper = false, lightScreen = true))
        assertFalse(statusBarIconsDark(onPlayer = false, playerFillsScreen = true, lightWallpaper = true, lightScreen = false))
    }
}
