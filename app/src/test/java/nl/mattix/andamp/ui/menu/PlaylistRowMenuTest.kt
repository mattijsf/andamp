// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Winamp's right click on a playlist row.
 *
 * The verbs act on the selection, not on the row that was pressed, which is
 * what "item(s)" means in its labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistRowMenuTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        vm =
            WinampViewModel(
                app,
                createBackend = { scope -> MockBackend(FakeTracks.tracks, scope) },
                presetStore = InMemoryEqPresetStore(),
            )
        settle()
    }

    private fun settle() {
        repeat(SETTLE) { shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    private fun menu(row: Int) = playlistRowMenu(vm, row, MenuAnchor(WindowStore.PLAYLIST, 0, 0, 10, 10))

    private fun click(
        row: Int,
        label: String,
    ) {
        val item = menu(row).items.filterIsInstance<AmpMenuItem.Action>().first { it.label == label }
        item.onClick()
        settle()
    }

    @Test
    fun `the menu lists play, remove, crop, file info and bookmark`() {
        val labels = menu(0).items.filterIsInstance<AmpMenuItem.Action>().map { it.label }

        assertEquals(listOf("Play item(s)", "Remove item(s)", "Crop files", "File info", "Bookmark item(s)"), labels)
    }

    @Test
    fun `it is named for what it acts on`() {
        vm.playlistOps.selectTrack(2)

        assertEquals(vm.state.playlist[2].title, menu(2).title)

        vm.state.selectedRows = setOf(1, 2, 3)
        assertEquals("3 items", menu(1).title)
    }

    @Test
    fun `play starts the first of what is selected`() {
        vm.state.selectedRows = setOf(3, 5)

        click(3, "Play item(s)")

        assertEquals(3, vm.state.currentIndex)
        assertEquals(Transport.Playing, vm.state.transport)
    }

    @Test
    fun `remove takes the selected row`() {
        val doomed = vm.state.playlist[1].id
        vm.state.selectedRows = setOf(1)

        click(1, "Remove item(s)")

        assertTrue("the selected row is removed", vm.state.playlist.none { it.id == doomed })
    }

    @Test
    fun `crop keeps only the selection`() {
        val kept = vm.state.playlist[4].id
        vm.state.selectedRows = setOf(4)

        click(4, "Crop files")

        assertEquals(1, vm.state.playlist.size)
        assertEquals(
            kept,
            vm.state.playlist
                .single()
                .id,
        )
    }

    /** Nothing selected and nothing under the finger: no verb may be armed. */
    @Test
    fun `an empty playlist offers nothing to do`() {
        vm.playlistOps.removeAll()
        settle()

        val actions = menu(0).items.filterIsInstance<AmpMenuItem.Action>()

        assertTrue("no verb is enabled on an empty playlist", actions.none { it.enabled })
    }

    @Test
    fun `file info describes the selected row`() {
        vm.state.selectedRows = setOf(2)

        click(2, "File info")

        assertEquals(vm.state.playlist[2].title, vm.state.trackInfo?.heading)
    }

    /** Mock tracks have nowhere to come back to. */
    @Test
    fun `bookmark is disabled for a mock track`() {
        vm.state.selectedRows = setOf(0)

        val bookmark = menu(0).items.filterIsInstance<AmpMenuItem.Action>().first { it.label == "Bookmark item(s)" }

        assertFalse(bookmark.enabled)
    }

    private companion object {
        const val SETTLE = 20
    }
}
