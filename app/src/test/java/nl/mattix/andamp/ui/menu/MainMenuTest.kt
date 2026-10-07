// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h1600dp-mdpi")
class MainMenuTest {
    private companion object {
        const val SETTLE_MS = 5_000L
    }

    private fun actions(items: List<AmpMenuItem>) = items.filterIsInstance<AmpMenuItem.Action>()

    private fun submenu(
        menu: nl.mattix.andamp.state.AmpMenu,
        label: String,
    ) = menu.items
        .filterIsInstance<AmpMenuItem.Submenu>()
        .first { it.label == label }
        .items

    @Test
    fun `menu anchors at the titlebar options button`() {
        val menu = mainMenu(testViewModel(), {}, {}, {}, {})
        assertEquals("Winamp", menu.title)
        assertEquals(MenuAnchor("main", 6, 3, 9, 9), menu.anchor)
    }

    @Test
    fun `the root is Winamp's root, plus the windows this player has that Winamp did not`() {
        // Follows Winamp 2.9's main menu: submenus, the windows it can show,
        // and Exit. The media library is one of this player's windows, so it is
        // listed beside the two Winamp had.
        val vm = testViewModel()
        val menu = mainMenu(vm, {}, {}, {}, {})

        // the library is one entry where there is one source and a submenu of
        // sources where there are more
        val asks = vm.librarySources.present.size > 1
        assertEquals(
            listOf("View file info", "Playlist Editor", "Equalizer") +
                listOfNotNull("Media Library".takeIf { !asks }) +
                listOf("Support Andamp...", "Exit"),
            actions(menu.items).map { it.label },
        )
        assertEquals(
            listOf("Play", "Bookmarks") +
                listOfNotNull("Media Library".takeIf { asks }) +
                listOf("Options", "Playback", "Visualization", "Skins"),
            menu.items.filterIsInstance<AmpMenuItem.Submenu>().map { it.label },
        )
    }

    @Test
    fun `Support Andamp opens Preferences on the page with the tips`() {
        val vm = testViewModel()
        var opened = 0
        val menu = mainMenu(vm, {}, {}, {}, {}, onPreferences = { opened++ })

        actions(menu.items).first { it.label == "Support Andamp..." }.onClick()

        assertEquals(1, opened)
        assertEquals("support", vm.state.arrivalPage)
    }

    /** The entry calls the callback it is given and keeps no state of its own. */
    @Test
    fun `always on top is listed under Options, unticked while off, and calls its callback`() {
        val vm = testViewModel()
        var asked = 0

        val options = actions(optionsMenu(vm, onAlwaysOnTop = { asked++ }).items)
        val entry = options.first { it.label == "Always On Top" }
        entry.onClick()

        assertEquals(false, entry.checked)
        assertEquals("the entry calls its callback once", 1, asked)
    }

    @Test
    fun `double size is listed right under always on top, as in Winamp`() {
        val labels = actions(optionsMenu(testViewModel(), onDoubleSize = {}).items).map { it.label }

        assertEquals(labels.indexOf("Always On Top") + 1, labels.indexOf("Double Size"))
    }

    @Test
    fun `double size calls its callback and is ticked while it is on`() {
        val vm = testViewModel()
        var asked = 0

        fun entry() = actions(optionsMenu(vm, onDoubleSize = { asked++ }).items).first { it.label == "Double Size" }

        vm.doubleSize.on = false
        assertEquals(false, entry().checked)
        vm.doubleSize.on = true
        assertEquals("ticked from the setting, wherever the menu is opened", true, entry().checked)
        assertEquals(true, entry().enabled)
        entry().onClick()
        assertEquals("the entry calls its callback once", 1, asked)
    }

    /**
     * Winamp's View file info describes what has been heard this session, not
     * what is queued: disabled on a fresh launch, and still enabled after the
     * playlist has been emptied.
     */
    @Test
    fun `view file info is dark until something has played`() {
        val vm = testViewModel()

        val entry = actions(mainMenu(vm, {}, {}, {}, {}).items).first { it.label == "View file info" }

        assertEquals(false, entry.enabled)
    }

    @Test
    fun `it lights up on what is playing`() {
        val vm = testViewModel()
        vm.play()
        settle { vm.state.lastPlayed != null }

        val entry = actions(mainMenu(vm, {}, {}, {}, {}).items).first { it.label == "View file info" }
        entry.onClick()
        settle { vm.state.trackInfo != null }

        assertEquals(true, entry.enabled)
        assertEquals(vm.state.playlist[vm.state.currentIndex].title, vm.state.trackInfo?.heading)
    }

    @Test
    fun `it still describes the last item when the playlist has been emptied`() {
        val vm = testViewModel()
        vm.play()
        settle { vm.state.lastPlayed != null }
        val heard = vm.state.lastPlayed!!.title

        vm.playlistOps.removeAll()
        settle { vm.state.playlist.isEmpty() }

        val entry = actions(mainMenu(vm, {}, {}, {}, {}).items).first { it.label == "View file info" }
        entry.onClick()
        settle { vm.state.trackInfo != null }

        assertEquals(true, entry.enabled)
        assertEquals(heard, vm.state.trackInfo?.heading)
    }

    /** Drives the looper until [until] holds; the mirror runs on the main thread. */
    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (!until() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
        }
    }

    @Test
    fun `the Bookmarks submenu has edit and add entries`() {
        // Winamp's order: edit them, add what is playing, then the bookmarks.
        // Adding takes no dialog, so it has no ellipsis
        val vm = testViewModel()
        val menu = mainMenu(vm, {}, {}, {}, {})

        val entries = actions(submenu(menu, "Bookmarks")).map { it.label }

        assertEquals(listOf("Edit bookmarks...", "Add current as bookmark"), entries)
    }

    @Test
    fun `under Play the Bookmark submenu is empty while nothing is bookmarked`() {
        // Winamp lists the bookmarks there without the two entries that manage them
        val menu = mainMenu(testViewModel(), {}, {}, {}, {})

        val bookmark =
            menu.items
                .filterIsInstance<AmpMenuItem.Submenu>()
                .first { it.label == "Play" }
                .items
                .filterIsInstance<AmpMenuItem.Submenu>()
                .first { it.label == "Bookmark" }

        assertEquals(emptyList<String>(), bookmark.items.map { (it as AmpMenuItem.Action).label })
    }

    @Test
    fun `preferences, repeat and shuffle are under Options`() {
        val options = submenu(mainMenu(testViewModel(), {}, {}, {}, {}), "Options")

        assertTrue(
            actions(options).map { it.label }.containsAll(
                listOf("Preferences...", "Repeat", "Shuffle"),
            ),
        )
    }

    @Test
    fun `the library is listed after Equalizer and ticked while it is showing`() {
        val menu = mainMenu(testViewModel(), {}, {}, {}, {})
        val listed = menu.items.indexOfFirst { labelOf(it) == "Media Library" }
        val windows = menu.items.indexOfFirst { labelOf(it) == "Equalizer" }
        assertTrue("the library is listed after Equalizer", listed == windows + 1)

        val phone = listOf(MusicSource.LOCAL)
        assertEquals(false, (libraryItem(phone, showing = null, onToggle = {}, onSource = {}) as AmpMenuItem.Action).checked)
        val open = libraryItem(phone, showing = MusicSource.LOCAL, onToggle = {}, onSource = {})
        assertEquals(true, (open as AmpMenuItem.Action).checked)
    }

    @Test
    fun `the library entry calls its toggle`() {
        var toggled = 0

        val entry = libraryItem(listOf(MusicSource.LOCAL), showing = MusicSource.LOCAL, onToggle = { toggled++ }, onSource = {})

        (entry as AmpMenuItem.Action).onClick()
        assertEquals(1, toggled)
    }

    /** What an entry is called, whichever kind of entry it is. */
    private fun labelOf(item: AmpMenuItem): String? =
        when (item) {
            is AmpMenuItem.Action -> item.label
            is AmpMenuItem.Submenu -> item.label
            else -> null
        }

    @Test
    fun `with more than one source the library asks which, and ticks the one on screen`() {
        val picked = mutableListOf<MusicSource>()
        val example = MusicSource("EXAMPLE", "Example")
        val both = listOf(MusicSource.LOCAL, example)

        val closed = libraryItem(both, showing = null, onToggle = {}, onSource = { picked += it })
        val open = libraryItem(both, showing = example, onToggle = {}, onSource = { picked += it })

        assertTrue("with two sources the library is a submenu", closed is AmpMenuItem.Submenu)
        val entries = actions((open as AmpMenuItem.Submenu).items)
        assertEquals(listOf("This Phone", "Example"), entries.map { it.label })
        assertEquals("the source on screen is ticked", listOf(false, true), entries.map { it.checked })
        assertEquals(
            "no source is ticked while the library is closed",
            listOf(false, false),
            actions((closed as AmpMenuItem.Submenu).items).map { it.checked },
        )

        entries.first().onClick()
        assertEquals(listOf(MusicSource.LOCAL), picked)
    }

    @Test
    fun `with one source the library is one entry`() {
        var toggled = 0

        val item = libraryItem(listOf(MusicSource.LOCAL), showing = null, onToggle = { toggled++ }, onSource = {})

        assertEquals("Media Library", (item as AmpMenuItem.Action).label)
        item.onClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `the skins submenu is Winamp's, with the museum where more skins were`() {
        val skins = submenu(mainMenu(testViewModel(), {}, {}, {}, {}), "Skins")

        assertEquals(
            listOf("Skin Browser...", "Load skin...", SkinEntry.BASE_NAME, "<< Get more skins! >>"),
            actions(skins).map { it.label },
        )
    }

    @Test
    fun `the skin being worn is the one with the tick`() {
        val menu = mainMenu(testViewModel(), {}, {}, {}, {})

        val base = actions(submenu(menu, "Skins")).first { it.label == SkinEntry.BASE_NAME }
        assertTrue("the worn skin is ticked", base.checked)
    }

    @Test
    fun `the windows tick themselves`() {
        val vm = testViewModel()
        vm.state.eqVisible = false

        val menu = mainMenu(vm, {}, {}, {}, {})

        assertEquals(false, actions(menu.items).first { it.label == "Equalizer" }.checked)
        assertEquals(true, actions(menu.items).first { it.label == "Playlist Editor" }.checked)
    }

    @Test
    fun `play file and exit fire their callbacks, load skin fires the picker`() {
        var opened = false
        var picked = false
        var exited = false
        val menu = mainMenu(testViewModel(), { opened = true }, {}, { picked = true }, { exited = true })

        actions(submenu(menu, "Play")).first { it.label == "Play file..." }.onClick()
        actions(menu.items).first { it.label == "Exit" }.onClick()
        val skins = menu.items.filterIsInstance<AmpMenuItem.Submenu>().first { it.label == "Skins" }
        actions(skins.items).first { it.label == "Load skin..." }.onClick()

        assertTrue(opened)
        assertTrue(exited)
        assertTrue(picked)
    }

    @Test
    fun `the base skin row clears the persisted skin`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val library = SkinLibrary(app)
        val saved = library.save(ByteArray(16) { 1 }, "Saved.wsz")
        library.currentId = saved.id

        val vm = testViewModel() // uses the same app-backed SkinLibrary by default
        val skins = mainMenu(vm, {}, {}, {}, {}).items.filterIsInstance<AmpMenuItem.Submenu>().first { it.label == "Skins" }
        actions(skins.items).first { it.label == SkinEntry.BASE_NAME }.onClick()

        val deadline = System.currentTimeMillis() + 5_000
        while (library.currentId != SkinEntry.BASE_ID && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(SkinEntry.BASE_ID, library.currentId)
        // the file stays in the library: Base skin switches, it does not delete
        assertEquals(true, library.open(saved.id) != null)
    }

    @Test
    fun `preferences and the museum are places the menu sends you`() {
        var toPreferences = false
        var toMuseum = false
        val menu =
            mainMenu(
                testViewModel(),
                {},
                {},
                {},
                {},
                onPreferences = { toPreferences = true },
                onMuseum = { toMuseum = true },
            )

        actions(submenu(menu, "Options")).first { it.label == "Preferences..." }.onClick()
        actions(submenu(menu, "Skins")).first { it.label == "<< Get more skins! >>" }.onClick()

        assertTrue("the Options entry opens Preferences", toPreferences)
        assertTrue(toMuseum)
    }
}
