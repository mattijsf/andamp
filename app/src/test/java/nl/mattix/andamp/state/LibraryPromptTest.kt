// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A song from the phone's library pressed while the audio permission is off.
 *
 * Its `content://media/` address opens only with the permission, so the press
 * sets [WinampState.libraryAsk], and [WinampViewModel.accessGiven] plays the
 * song once the permission is given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryPromptTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val picked =
        Track(
            "picked",
            "Boards of Canada",
            "Roygbiv",
            150_000,
            uri = "content://com.android.externalstorage.documents/document/primary%3ARoygbiv.mp3",
        )
    private val fromLibrary = Track("library", "Aphex Twin", "Xtal", 290_000, uri = "content://media/external/audio/media/42")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun viewModel() =
        WinampViewModel(
            app,
            createBackend = { scope -> MockBackend(listOf(picked, fromLibrary), scope) },
            presetStore = InMemoryEqPresetStore(),
        ).also { idle() }

    @Test
    fun `a library song pressed without the permission asks straight away, and plays once allowed`() {
        val vm = viewModel()

        vm.playTrack(1)
        idle()

        assertNull("no dialog of the app's shows before the system's", vm.state.prompt)
        val asked = vm.state.libraryAsk
        assertEquals("the ask carries the pressed song", fromLibrary, asked?.then)

        vm.playTrack(0)
        idle()
        vm.accessGiven(asked!!)
        idle()

        assertEquals("the pressed song plays", 1, vm.state.currentIndex)
    }

    @Test
    fun `a picked file is not asked about`() {
        val vm = viewModel()

        vm.playTrack(0)
        idle()

        assertNull(vm.state.libraryAsk)
    }

    @Test
    fun `play on a library song without the permission asks too`() {
        val vm = viewModel()
        vm.playTrack(1)
        idle()
        vm.state.libraryAsk = null

        vm.play()
        idle()

        assertEquals(fromLibrary, vm.state.libraryAsk?.then)
    }

    @Test
    fun `with the permission held a library song is not asked about`() {
        shadowOf(app).grantPermissions(MediaStoreAudio.permission())
        val vm = viewModel()

        vm.playTrack(1)
        idle()

        assertNull(vm.state.libraryAsk)
    }

    @Test
    fun `the settings prompt opens settings and closes itself`() {
        var opened = false
        var closed = false
        val prompt = LibraryPrompt.settings(onDone = { closed = true }) { opened = true }

        assertEquals("Music and audio permission is off", prompt.title)
        assertEquals("Open settings", prompt.confirmLabel)
        prompt.onConfirm()

        assertEquals(true, opened && closed)
    }
}
