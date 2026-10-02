// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.playback.PlaybackBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Winamp's Stop with fadeout and Stop after current track, and the two jumps,
 * to a time and to a file.
 *
 * A fade is the backend's own gain and stopping after the current track is the
 * backend's advance, so the tests assert that the view model asks the backend.
 * A backend that overrides neither does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackVerbsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Records the two calls; everything else is delegated to a [MockBackend]. */
    private class Recording(
        inner: MockBackend,
    ) : PlaybackBackend by inner {
        var fadedOut = 0
        var stopAfterCurrent: Boolean? = null

        override fun stopWithFadeout() {
            fadedOut++
        }

        override fun setStopAfterCurrent(on: Boolean) {
            stopAfterCurrent = on
        }
    }

    private lateinit var backend: Recording
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        vm =
            WinampViewModel(
                app,
                createBackend = { scope: CoroutineScope ->
                    Recording(MockBackend(FakeTracks.tracks, scope)).also { backend = it }
                },
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

    @Test
    fun `stop with fadeout asks the backend`() {
        vm.play()

        vm.stopWithFadeout()

        assertEquals(1, backend.fadedOut)
    }

    @Test
    fun `stop after current is kept in the state and passed to the backend`() {
        vm.setStopAfterCurrent(true)

        assertTrue("the state holds stop after current", vm.state.stopAfterCurrent)
        assertEquals(true, backend.stopAfterCurrent)

        vm.setStopAfterCurrent(false)
        assertEquals(false, backend.stopAfterCurrent)
    }

    @Test
    fun `a backend that overrides neither still takes both calls`() {
        val plain =
            WinampViewModel(
                app,
                createBackend = { scope: CoroutineScope -> MockBackend(FakeTracks.tracks, scope) },
                presetStore = InMemoryEqPresetStore(),
            )

        plain.stopWithFadeout()
        plain.setStopAfterCurrent(true)

        assertTrue(plain.state.stopAfterCurrent)
    }

    @Test
    fun `jump to time asks for one and moves the track to it`() {
        vm.play()
        settle { vm.state.transport == nl.mattix.andamp.core.model.Transport.Playing }

        vm.promptJumpToTime()
        val prompt = assertNotNull("a time prompt is shown", vm.state.namePrompt).let { vm.state.namePrompt!! }
        prompt.onSubmit("0:03")
        settle { vm.state.currentTimeSec >= 3 }

        assertTrue("the track moves to 0:03: at ${vm.state.currentTimeSec}s", vm.state.currentTimeSec >= 3)
    }

    @Test
    fun `a time that is not a time moves nothing`() {
        vm.play()
        settle { vm.state.transport == nl.mattix.andamp.core.model.Transport.Playing }
        val before = vm.state.currentTimeSec

        vm.promptJumpToTime()
        vm.state.namePrompt!!.onSubmit("half past two")

        assertEquals(before, vm.state.currentTimeSec)
    }

    @Test
    fun `jump to file lists the queue and plays what is chosen`() {
        vm.promptJumpToFile()

        val picker = assertNotNull("a list of the queue is shown", vm.state.presetPicker).let { vm.state.presetPicker!! }
        assertEquals(vm.state.playlist.size, picker.entries.size)
        picker.onConfirm(listOf("3"))
        settle { vm.state.currentIndex == 3 }

        assertEquals(3, vm.state.currentIndex)
    }

    @Test
    fun `jump to file with an empty queue asks nothing`() {
        vm.playlistOps.removeAll()
        settle { vm.state.playlist.isEmpty() }

        vm.promptJumpToFile()

        assertNull(vm.state.presetPicker)
    }

    private companion object {
        const val SETTLE_MS = 5_000L
    }
}
