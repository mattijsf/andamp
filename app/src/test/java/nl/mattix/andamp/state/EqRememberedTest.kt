// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The equalizer a listener left is the equalizer the next launch shows and plays through:
 * ON, AUTO, the preamp and the bands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EqRememberedTest {
    private lateinit var app: Application
    private lateinit var scope: CoroutineScope

    /** A backend that keeps the equalizer it was last given. */
    private class Hears(
        inner: PlaybackBackend,
    ) : PlaybackBackend by inner {
        var heard: EqSettings? = null

        override fun setEqualizer(settings: EqSettings) {
            heard = settings
        }
    }

    /** One launch of the app: its screen state, its backend, and what it leaves behind. */
    private inner class Launch {
        val state = WinampState()
        val backend = Hears(MockBackend(FakeTracks.tracks, scope))
        private val facade = PlayerFacade(backend)
        val eq = EqOps(state, facade, InMemoryEqPresetStore())
        val persistence =
            PersistenceOps(
                state,
                facade,
                scope,
                PlaylistStore(app),
                TransportStore(app),
                WindowStore(app),
                VisualsStore(app),
                EqStore(app),
                io = Dispatchers.Unconfined,
            ).also { it.start() }

        /** The app going to the background, which is where a pending write is written. */
        fun leave() {
            // the writer collects a snapshotFlow, which emits once apply notifications are sent
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            persistence.flush()
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("windows", "transport", "visuals", "equalizer").forEach {
            app
                .getSharedPreferences(it, 0)
                .edit()
                .clear()
                .commit()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @Test
    fun `a preset that was loaded is still on the sliders after a restart`() {
        val rock = EqFactoryPresets.ALL.first { it.name == "Rock" }
        Launch().apply {
            eq.applyPreset(rock)
            leave()
        }

        val next = Launch()

        assertEquals(rock.bands, next.state.eqBands.toList())
        assertEquals(rock.preamp, next.state.preamp)
    }

    @Test
    fun `the restored curve is what the music plays through`() {
        val rock = EqFactoryPresets.ALL.first { it.name == "Rock" }
        Launch().apply {
            eq.applyPreset(rock)
            leave()
        }

        val next = Launch()

        assertEquals(rock.bands.map(EqOps::eqDb), next.backend.heard?.bandsDb)
    }

    @Test
    fun `sliders moved by hand come back where they were left`() {
        Launch().apply {
            eq.setBand(0, 50)
            eq.setBand(9, 7)
            eq.setPreamp(40)
            leave()
        }

        val next = Launch()

        assertEquals(50, next.state.eqBands[0])
        assertEquals(7, next.state.eqBands[9])
        assertEquals(40, next.state.preamp)
    }

    @Test
    fun `ON and AUTO come back as they were left`() {
        Launch().apply {
            eq.toggleOn()
            state.eqAuto = true
            leave()
        }

        val next = Launch()

        assertFalse(next.state.eqOn)
        assertTrue(next.state.eqAuto)
        assertEquals(false, next.backend.heard?.enabled)
    }

    @Test
    fun `a first launch has a flat equalizer that is on, and stores nothing`() {
        val first = Launch()

        assertTrue(first.state.eqOn)
        assertFalse(first.state.eqAuto)
        assertEquals(List(10) { EqOps.CENTER }, first.state.eqBands.toList())
        assertEquals(EqSettings.FLAT, first.backend.heard)
        first.leave()
        assertNull(EqStore(app).load())
    }

    @Test
    fun `a launch that changes nothing keeps what was stored`() {
        val rock = EqFactoryPresets.ALL.first { it.name == "Rock" }
        Launch().apply {
            eq.applyPreset(rock)
            leave()
        }

        Launch().leave()

        assertEquals(rock.bands, EqStore(app).load()?.bands)
    }
}
