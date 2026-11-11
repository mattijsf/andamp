// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status bar's icons are dark over something light, whatever it is that is under the bar. */
class StatusBarIconsTest {
    @Test
    fun `over the floating player the wallpaper decides`() {
        assertTrue(statusBarIconsDark(onPlayer = true, lightWallpaper = true, lightScreen = false))
        assertFalse(statusBarIconsDark(onPlayer = true, lightWallpaper = false, lightScreen = true))
    }

    @Test
    fun `any other screen decides for itself, whatever the wallpaper`() {
        assertTrue(statusBarIconsDark(onPlayer = false, lightWallpaper = false, lightScreen = true))
        assertFalse(statusBarIconsDark(onPlayer = false, lightWallpaper = true, lightScreen = false))
    }
}
