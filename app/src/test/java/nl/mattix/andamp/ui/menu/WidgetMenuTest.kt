// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.modernskin.fixtures.optionsItem
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A view model that wore a modern skin; let go after the test. */
    private var wearing: WinampViewModel? = null

    @After
    fun letGoOfModernSkin() {
        wearing?.let { letGoOfModernSkin(app, it) }
    }

    private fun menu(vm: WinampViewModel = this.vm) =
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
            "Double Size",
        ).forEach { label ->
            assertNotNull("$label is on the menu", find(label))
            assertFalse("$label is grayed on the widget", enabledOf(label))
        }
    }

    @Test
    fun `a worn modern skin's color themes are on it, and grayed`() {
        val worn = wearThemedModernSkin(app, "One", "Two").also { wearing = it }

        val themes = find("Color Themes", menu(worn).items)

        assertNotNull("Color Themes is on the menu", themes)
        assertFalse("Color Themes is grayed on the widget", (themes as AmpMenuItem.Submenu).enabled)
    }

    @Test
    fun `after a cold start from the widget's menu the modern skin has not loaded, so it offers no color themes`() {
        val worn = wearThemedModernSkin(app, "One", "Two", started = false).also { wearing = it }

        assertNotNull("the modern skin is worn", worn.skinOps.wal)
        assertNull(find("Color Themes", menu(worn).items))
    }

    @Test
    fun `a worn modern skin's options are on it, and grayed`() {
        val worn = wearThemedModernSkin(app).also { wearing = it }
        seedSkinOptions(worn.skinOps.wal!!) { rt -> rt.optionsItem().option("Option", "1") }

        val settings = find("Skin Settings...", menu(worn).items)

        assertNotNull("Skin Settings is on the menu", settings)
        assertFalse("Skin Settings is grayed on the widget", (settings as AmpMenuItem.Action).enabled)
    }

    @Test
    fun `after a cold start the skin has not loaded, so it offers no Skin Settings`() {
        val worn = wearThemedModernSkin(app, started = false).also { wearing = it }

        assertNotNull("the modern skin is worn", worn.skinOps.wal)
        assertNull(find("Skin Settings...", menu(worn).items))
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
