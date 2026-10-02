// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The menu the widget's own button opens.
 *
 * It is the player's menu on another surface: the entries are the app's own, and
 * the ones a home screen cannot answer are grayed and stay in their place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetMenuTest {
    private val vm =
        WinampViewModel(
            ApplicationProvider.getApplicationContext(),
            createBackend = { MockBackend(FakeTracks.tracks, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) },
        )

    private val opened = mutableListOf<String?>()

    private fun menu() =
        widgetMenu(
            vm,
            onWidgetSettings = {},
            onPickSkin = {},
            onOpenFile = {},
            onOpenFolder = {},
            onOpen = { opened += it },
            onExit = {},
        )

    private fun find(
        label: String,
        items: List<AmpMenuItem> = menu().items,
    ): AmpMenuItem? =
        items.firstOrNull { it is AmpMenuItem.Action && it.label == label }
            ?: items.firstOrNull { it is AmpMenuItem.Submenu && it.label == label }
            ?: items.filterIsInstance<AmpMenuItem.Submenu>().firstNotNullOfOrNull { find(label, it.items) }

    private fun enabledOf(label: String) =
        when (val item = find(label)) {
            is AmpMenuItem.Action -> item.enabled
            is AmpMenuItem.Submenu -> item.enabled
            else -> error("$label is not on the menu")
        }

    @Test
    fun `the widget's own settings are the first thing on it`() {
        assertEquals("Widget settings...", (menu().items.first() as AmpMenuItem.Action).label)
    }

    @Test
    fun `what needs the app is still there, and grayed`() {
        // a dialog is not on this list, because the menu's host draws those wherever it is
        listOf(
            "Playlist Editor",
            "Equalizer",
            "Media Library",
            "Visualization",
            "Skin Browser...",
            "Always On Top",
        ).forEach { label ->
            assertNotNull("$label is on the menu", find(label))
            assertFalse("$label is grayed on the widget", enabledOf(label))
        }
    }

    @Test
    fun `what works from a home screen is left alone`() {
        // the verbs act on the player; Preferences and the museum are screens opened by name
        listOf("Play", "Stop", "Next", "Repeat", "Shuffle", "Time remaining", "Preferences...", "<< Get more skins! >>")
            .forEach { assertTrue("$it is enabled on the widget", enabledOf(it)) }
    }

    @Test
    fun `dialogs and pickers stay enabled`() {
        // each of these opens a dialog, which AmpModals draws wherever it is hosted
        vm.playTrack(0)

        listOf("View file info", "Jump to time...", "Jump to file...").forEach {
            assertTrue("$it opens a dialog and is enabled on the widget", enabledOf(it))
        }
        assertFalse(
            "Edit bookmarks... opens a sheet that does not need the app",
            (find("Edit bookmarks...") as AmpMenuItem.Action).needsTheApp,
        )
        // a picker belongs to the host that opened the menu
        listOf("Load skin...", "Play file...", "Play directory...").forEach {
            assertTrue("$it opens the host's own picker and is enabled", enabledOf(it))
        }
    }

    @Test
    fun `preferences and the museum are opened by screen name`() {
        (find("<< Get more skins! >>") as AmpMenuItem.Action).onClick()
        (find("Preferences...") as AmpMenuItem.Action).onClick()

        assertEquals(listOf(Screen.MUSEUM, Screen.PREFERENCES), opened)
    }

    @Test
    fun `showing track info opens the box at once with the row's title`() {
        // a host with nothing behind the menu closes when the menu does, so the
        // box opens at once with the queue's own row
        vm.playTrack(0)
        val track = vm.state.playlist.first()

        vm.trackInfoOps.show(track)

        assertNotNull("the track info box opens", vm.state.trackInfo)
        assertEquals(track.title, vm.state.trackInfo?.heading)
    }
}
