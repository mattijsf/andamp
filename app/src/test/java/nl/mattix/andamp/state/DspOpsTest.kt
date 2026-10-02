// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preferences > Plug-ins > DSP/Effect: the rack reaches the backend, and it is remembered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspOpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun clearRack() {
        // DspOps remembers the rack; without this each test inherits the last one's
        app
            .getSharedPreferences("dsp", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private class EffectBackend(
        scope: CoroutineScope,
        val applied: MutableList<RackSettings> = mutableListOf(),
    ) : PlaybackBackend by MockBackend(FakeTracks.tracks, scope) {
        private val delegate = MockBackend(FakeTracks.tracks, scope)
        override val state get() = delegate.state
        override val capabilities: Capabilities get() = delegate.capabilities.copy(hasDsp = true)

        override fun setDsp(rack: RackSettings) {
            applied += rack
        }
    }

    private fun opsWith(backend: PlaybackBackend) = DspOps(app, PlayerFacade(backend))

    @Test
    fun `the rack starts as every bundled effect, in signal order, switched off`() {
        val ops = opsWith(EffectBackend(scope))

        assertEquals(BuiltInEffects.ids, ops.rack.slots.map { it.pluginId })
        assertTrue(ops.rack.slots.none { it.enabled })
    }

    @Test
    fun `switching an effect on reaches the backend`() {
        val backend = EffectBackend(scope)
        val ops = opsWith(backend)

        ops.setEnabled(BuiltInEffects.REVERB, true)
        ops.setValue(BuiltInEffects.REVERB, "size", 0.9f)

        val sent = backend.applied.last()
        assertTrue(sent[BuiltInEffects.REVERB]!!.enabled)
        assertEquals(0.9f, sent[BuiltInEffects.REVERB]!!.params["size", 0f], 0f)
    }

    @Test
    fun `switching it off again reaches the backend too`() {
        val backend = EffectBackend(scope)
        val ops = opsWith(backend)

        ops.setEnabled(BuiltInEffects.REVERB, true)
        ops.setEnabled(BuiltInEffects.REVERB, false)

        assertFalse(backend.applied.last()[BuiltInEffects.REVERB]!!.enabled)
        assertFalse(ops.rack[BuiltInEffects.REVERB]!!.enabled)
    }

    @Test
    fun `reordering reaches the backend`() {
        val backend = EffectBackend(scope)
        val ops = opsWith(backend)

        ops.moveDown(BuiltInEffects.KARAOKE)

        assertEquals(
            BuiltInEffects.MODULATION,
            ops.rack.slots
                .first()
                .pluginId,
        )
        assertEquals(ops.rack, backend.applied.last())
    }

    @Test
    fun `the first slot will not move up and the last will not move down`() {
        val ops = opsWith(EffectBackend(scope))
        val original = ops.rack

        ops.moveUp(BuiltInEffects.ids.first())
        ops.moveDown(BuiltInEffects.ids.last())

        assertEquals(original, ops.rack)
    }

    @Test
    fun `the rack survives a relaunch and is pushed on start`() {
        opsWith(EffectBackend(scope)).apply {
            setEnabled(BuiltInEffects.MODULATION, true)
            setValue(BuiltInEffects.MODULATION, "depth", 0.7f)
            moveUp(BuiltInEffects.MODULATION)
        }

        val backend = EffectBackend(scope)
        val relaunched = opsWith(backend)
        relaunched.start()

        assertEquals(
            BuiltInEffects.MODULATION,
            relaunched.rack.slots
                .first()
                .pluginId,
        )
        assertTrue(relaunched.rack[BuiltInEffects.MODULATION]!!.enabled)
        assertEquals(0.7f, relaunched.rack[BuiltInEffects.MODULATION]!!.params["depth", 0f], 0f)
        assertEquals(relaunched.rack, backend.applied.single())
    }

    @Test
    fun `removing a plug-in takes its remembered state with it`() {
        opsWith(EffectBackend(scope)).apply {
            setEnabled(BuiltInEffects.REVERB, true)
            remove(BuiltInEffects.REVERB)
        }

        // the bundled effect is still installed, so it returns switched off
        val relaunched = opsWith(EffectBackend(scope))

        assertFalse(relaunched.rack[BuiltInEffects.REVERB]!!.enabled)
    }

    @Test
    fun `a backend that runs no effects reports the rack as unavailable`() {
        val ops = opsWith(MockBackend(FakeTracks.tracks, scope))

        assertTrue("the mock backend has no DSP", !ops.available)
    }

    /** A backend that offers the Preamp last among its effects. */
    private class PreampBackend(
        scope: CoroutineScope,
    ) : PlaybackBackend by MockBackend(FakeTracks.tracks, scope) {
        private val delegate = MockBackend(FakeTracks.tracks, scope)
        override val state get() = delegate.state
        override val capabilities: Capabilities get() = delegate.capabilities.copy(hasDsp = true)
        override val effects =
            BuiltInEffects.all +
                nl.mattix.andamp.core.model
                    .EffectSpec(id = BuiltInEffects.PREAMP, name = "Preamp", params = emptyList())
    }

    @Test
    fun `the Preamp stays first in the rack`() {
        val ops = opsWith(PreampBackend(scope))
        assertEquals(
            "the Preamp is first on a fresh rack",
            BuiltInEffects.PREAMP,
            ops.rack.slots
                .first()
                .pluginId,
        )

        ops.moveTo(BuiltInEffects.PREAMP, 2)
        ops.moveDown(BuiltInEffects.PREAMP)
        assertEquals(
            "the Preamp stays first after a move down",
            BuiltInEffects.PREAMP,
            ops.rack.slots
                .first()
                .pluginId,
        )

        ops.moveTo(BuiltInEffects.REVERB, 0)
        assertEquals(
            "an effect moved to the top lands below the Preamp",
            listOf(
                BuiltInEffects.PREAMP,
                BuiltInEffects.REVERB,
            ),
            ops.rack.slots.take(2).map {
                it.pluginId
            },
        )
        assertTrue(ops.pinned(BuiltInEffects.PREAMP))
    }
}
