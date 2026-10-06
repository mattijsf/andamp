// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pressing a row whose folder can no longer be read. The player would skip such a row and
 * play the next one, which says nothing about why. The press asks for the folder instead,
 * and the row plays once the folder is given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LostFolderPressTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val road = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRoad"
    private val lost = Track("lost", "", "one.mp3", 60_000, uri = "$road/document/primary%3AMusic%2FRoad%2Fone.mp3")
    private val fine = Track("fine", "", "two.mp3", 60_000, uri = "content://media/external/audio/media/2")

    @Before
    fun setUp() {
        File(app.filesDir, "winamp.m3u").delete()
    }

    private fun vm(): WinampViewModel =
        WinampViewModel(
            app,
            createBackend = { scope -> MockBackend(listOf(fine, lost), scope) },
            presetStore = InMemoryEqPresetStore(),
            playlistStore = PlaylistStore(app),
        ).also { vm ->
            settle()
            assertEquals(listOf("fine", "lost"), vm.state.playlist.map { it.id })
        }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))

    /**
     * Whether the library lists a pressed row's file is asked off the main thread, and the
     * answer comes back to it. This waits for that, in real time, with the main thread's
     * queue run in between.
     */
    private fun await(condition: () -> Boolean) {
        repeat(AWAIT_ROUNDS) {
            settle()
            if (condition()) return
            Thread.sleep(AWAIT_STEP_MS)
        }
    }

    @Test
    fun `the press asks for the folder and does not move on to another row`() {
        val vm = vm()

        vm.playTrack(1)
        await { vm.state.prompt != null }

        assertEquals("Allow Music/Road again?", vm.state.prompt?.title)
        assertEquals(Transport.Stopped, vm.state.transport)
    }

    @Test
    fun `the row plays once the folder is given`() {
        val vm = vm()
        vm.playTrack(1)
        await { vm.state.prompt != null }

        vm.state.prompt
            ?.onConfirm
            ?.invoke()
        val ask = requireNotNull(vm.state.folderAsk) { "the screen is asked to open the picker" }
        vm.folderAccess.answered(ask, Uri.parse(road))
        settle()

        assertEquals(Transport.Playing, vm.state.transport)
        assertEquals("lost", vm.state.currentTrack?.id)
    }

    @Test
    fun `a row that can be read plays as before`() {
        val vm = vm()

        vm.playTrack(0)
        settle()

        assertNull(vm.state.prompt)
        assertEquals(Transport.Playing, vm.state.transport)
        assertEquals("fine", vm.state.currentTrack?.id)
    }

    @Test
    fun `play from a stop on such a row asks too`() {
        val vm = vm()
        vm.playTrack(0)
        settle()
        vm.next()
        vm.stop()
        settle()
        assertEquals("lost", vm.state.currentTrack?.id)

        vm.play()
        await { vm.state.prompt != null }

        assertEquals("Allow Music/Road again?", vm.state.prompt?.title)
        assertEquals(Transport.Stopped, vm.state.transport)
    }

    private companion object {
        const val AWAIT_ROUNDS = 200
        const val AWAIT_STEP_MS = 10L
    }
}
