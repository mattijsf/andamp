// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.MixedQueueBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A backend's notice as the listener meets it: shown each time it is raised, and not
 * again while the same notice stands.
 *
 * `BackendNoticesTest` covers the words. This covers the collector in [WinampViewModel]
 * that turns a notice into a dialog, once per [BackendState.noticeSeq].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackendNoticePromptTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A backend whose state the test sets; every call still goes to the mock. */
    private class Speaking(
        inner: MockBackend,
    ) : PlaybackBackend by inner {
        val says = MutableStateFlow(inner.state.value)
        override val state: StateFlow<BackendState> get() = says
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun viewModel(backend: (CoroutineScope) -> PlaybackBackend) =
        WinampViewModel(app, createBackend = backend, presetStore = InMemoryEqPresetStore()).also { idle() }

    /** A playlist whose one source cannot be reached: its player cannot be built, as with nobody signed in. */
    private fun nobodySignedIn(scope: CoroutineScope) =
        MixedQueueBackend(
            lanes = listOf(MixedQueueBackend.Lane(claims = { true }, make = { null })),
            tracks = FakeTracks.tracks,
            startIndex = 0,
            scope = scope,
        )

    /** Closed the way its only button closes it. */
    private fun WinampViewModel.closeTheNotice() {
        state.prompt?.onConfirm?.invoke()
        idle()
    }

    @Test
    fun `a second press that finds nothing to play shows the notice again`() {
        val vm = viewModel(::nobodySignedIn)
        vm.play()
        idle()
        assertEquals("Nothing here can play", vm.state.prompt?.title)
        vm.closeTheNotice()
        assertNull(vm.state.prompt)

        vm.play()
        idle()

        assertEquals("the second press shows the notice again", "Nothing here can play", vm.state.prompt?.title)
    }

    @Test
    fun `a closed notice stays closed while the player carries on around it`() {
        val vm = viewModel(::nobodySignedIn)
        vm.play()
        idle()
        vm.closeTheNotice()

        // a volume change publishes a new state carrying the same notice
        vm.setVolume(0.3f)
        idle()

        assertNull("a closed notice stays closed on a volume change", vm.state.prompt)
    }

    @Test
    fun `a notice raised again after it was closed is shown again`() {
        lateinit var player: Speaking
        val vm = viewModel { scope -> Speaking(MockBackend(FakeTracks.tracks, scope)).also { player = it } }
        player.says.value = player.says.value.raising(BackendNotice.SourceCannotPlay)
        idle()
        vm.closeTheNotice()

        player.says.value = player.says.value.raising(BackendNotice.SourceCannotPlay)
        idle()

        assertEquals("Nothing will play", vm.state.prompt?.title)
    }

    /**
     * A playlist over several players publishes the state of the player in use, so a
     * notice returns with the player that raised it after another player was in use.
     */
    @Test
    fun `a closed notice is not shown again when its player's state returns`() {
        lateinit var player: Speaking
        val vm = viewModel { scope -> Speaking(MockBackend(FakeTracks.tracks, scope)).also { player = it } }
        val raised = player.says.value.raising(BackendNotice.SourceCannotPlay)
        player.says.value = raised
        idle()
        vm.closeTheNotice()

        player.says.value = BackendState(queue = FakeTracks.tracks, currentIndex = 1)
        idle()
        player.says.value = raised.copy(currentIndex = 2)
        idle()

        assertNull("a closed notice stays closed when its player's state returns", vm.state.prompt)
    }
}
