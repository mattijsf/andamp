// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.backend.mock.MockBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Winamp's MISC > Sort list: sort by title, reverse and randomize.
 *
 * Each of them rewrites the queue through the facade, so the backend's queue is
 * reordered and the list on screen follows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistSortTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        vm =
            WinampViewModel(
                app,
                createBackend = { scope: CoroutineScope -> MockBackend(FakeTracks.tracks, scope) },
                presetStore = InMemoryEqPresetStore(),
            )
        settle { vm.state.playlist.isNotEmpty() }
    }

    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (!until() && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private fun titles() = vm.state.playlist.map { it.title }

    @Test
    fun `sorting by title orders the queue, not just the view`() {
        val expected = titles().sortedBy { it.lowercase() }

        vm.playlistOps.sortByTitle()
        settle { titles() == expected }

        assertEquals(expected, titles())
    }

    @Test
    fun `reversing turns the queue back to front`() {
        val expected = titles().reversed()

        vm.playlistOps.reverse()
        settle { titles() == expected }

        assertEquals(expected, titles())
    }

    @Test
    fun `randomizing deals the same tracks in another order`() {
        val before = titles()

        vm.playlistOps.randomize(Random(7))
        settle { titles() != before }

        assertEquals("the shuffle keeps every track", before.sorted(), titles().sorted())
        assertNotEquals(before, titles())
    }

    @Test
    fun `a sort clears the selection`() {
        vm.playlistOps.selectTrack(3)

        vm.playlistOps.reverse()
        settle { vm.state.selectedRows.isEmpty() }

        assertEquals(emptySet<Int>(), vm.state.selectedRows)
    }

    private companion object {
        const val SETTLE_MS = 5_000L
    }
}
