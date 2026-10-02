// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The equalizer's AUTO through the view model: a track that loads gets its own curve.
 *
 * [EqOps.trackChanged] holds the rule. This covers the collector that calls it once per
 * song: a queue entry can be replaced while its song plays, and reloading the curve then
 * would undo a slider the listener moved mid-song.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoPresetWiringTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = InMemoryEqPresetStore()
    private val tracks = FakeTracks.tracks

    private val curve = EqPreset("second song", 11, listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))

    private var backend: MockBackend? = null

    private fun viewModel(): WinampViewModel =
        WinampViewModel(
            app,
            createBackend = { scope -> MockBackend(tracks, scope).also { backend = it } },
            presetStore = store,
        ).also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a track that loads brings its own curve`() {
        store.autoPresets.put(TrackKey.of(tracks[1]), curve)
        val vm = viewModel()
        vm.state.eqAuto = true

        vm.playTrack(1)
        idle()

        assertEquals(curve.bands, vm.state.eqBands.toList())
        assertEquals(curve.preamp, vm.state.preamp)
    }

    @Test
    fun `with AUTO off the curve stays where the listener left it`() {
        store.autoPresets.put(TrackKey.of(tracks[1]), curve)
        val vm = viewModel()
        vm.eqOps.setBand(0, 60)

        vm.playTrack(1)
        idle()

        assertEquals(60, vm.state.eqBands[0])
    }

    @Test
    fun `the same track rewritten mid-song does not reload its curve`() {
        store.autoPresets.put(TrackKey.of(tracks[0]), curve)
        val vm = viewModel()
        vm.state.eqAuto = true
        vm.playTrack(0)
        idle()
        // the listener nudges a band after the curve loaded
        vm.eqOps.setBand(0, 63)

        // the same songs arrive again with another bitrate
        backend!!.setQueue(tracks.map { it.copy(bitrateKbps = 321) }, 0)
        idle()

        assertEquals(63, vm.state.eqBands[0])
    }
}
