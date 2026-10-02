// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoveDeadFilesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    // a real file on disk, so the test covers a file:// uri that MediaFiles.isAlive reports alive
    private val livingFile =
        java.io.File
            .createTempFile("alive", ".mp3")
            .apply { writeBytes(ByteArray(64)) }

    private val tracks =
        listOf(
            Track("bundled", "", "Andamp Whippin' Intro", 5000, uri = "asset:///audio/andamp-intro.mp3"),
            Track("on-disk", "", "still here", 1000, uri = "file://${livingFile.absolutePath}"),
            Track("dead-saf", "", "was on the sd card", 1000, uri = "content://com.gone.provider/document/42"),
            Track("dead-file", "", "deleted file", 1000, uri = "file:///nowhere/gone.mp3"),
            Track("no-uri", "", "mock track without a uri", 1000),
        )

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the condition holds within 10 s", condition())
    }

    @Test
    fun `rem misc drops unresolvable uris and keeps assets, real files and uri-less tracks`() {
        val vm =
            WinampViewModel(
                app,
                createBackend = { scope -> MockBackend(tracks, scope) },
                presetStore = InMemoryEqPresetStore(),
            )
        await { vm.state.playlist.size == 5 }

        vm.playlistFiles.removeDeadFiles()

        await { vm.state.playlist.size == 3 }
        assertEquals(listOf("bundled", "on-disk", "no-uri"), vm.state.playlist.map { it.id })
    }

    @Test
    fun `stale dead ids do not fire a queue edit that clears selection`() {
        val recording = mutableListOf<List<Track>>()
        val state = WinampState()
        state.playlist = listOf(Track("a", "", "x", 1000), Track("b", "", "y", 1000))
        state.selectedRows = setOf(0)
        val ops =
            PlaylistOps(
                state,
                nl.mattix.andamp.core.player
                    .PlayerFacade(QueueRecordingBackend(recording)),
            )
        ops.removeDead(setOf("already-gone")) // scanned before the row was removed
        assertTrue(recording.isEmpty())
        assertEquals(setOf(0), state.selectedRows)
    }

    @Test
    fun `a playlist with nothing dead is left untouched`() {
        val recording = mutableListOf<List<Track>>()
        val state = WinampState()
        state.playlist = listOf(Track("a", "", "x", 1000), Track("b", "", "y", 1000))
        state.selectedRows = setOf(1)
        val ops =
            PlaylistOps(
                state,
                nl.mattix.andamp.core.player
                    .PlayerFacade(QueueRecordingBackend(recording)),
            )
        ops.removeDead(emptySet())
        assertTrue(recording.isEmpty())
        assertEquals(setOf(1), state.selectedRows)
    }

    @Test
    fun `removeDead filters by id, not by index`() {
        val recording = mutableListOf<List<Track>>()
        val state = WinampState()
        state.playlist = listOf(Track("a", "", "x", 1000), Track("b", "", "y", 1000), Track("c", "", "z", 1000))
        val ops =
            PlaylistOps(
                state,
                nl.mattix.andamp.core.player
                    .PlayerFacade(QueueRecordingBackend(recording)),
            )
        ops.removeDead(setOf("b"))
        assertEquals(listOf("a", "c"), recording.single().map { it.id })
    }
}
