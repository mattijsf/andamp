// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.flow.MutableStateFlow
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captures pushed EQ settings; everything else is inert. */
class RecordingEqBackend : PlaybackBackend {
    val pushed = mutableListOf<EqSettings>()
    override val state = MutableStateFlow(BackendState())
    override val capabilities = Capabilities(canSeek = true, hasEqualizer = true)

    override fun setEqualizer(settings: EqSettings) {
        pushed += settings
    }

    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun stop() = Unit

    override fun next() = Unit

    override fun previous() = Unit

    override fun playAt(index: Int) = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun setVolume(fraction: Float) = Unit

    override fun setShuffle(enabled: Boolean) = Unit

    override fun setRepeat(enabled: Boolean) = Unit

    override fun release() = Unit
}

/** A [PlayerFacade] over [backend]. */
fun playerFacadeFor(backend: PlaybackBackend) = PlayerFacade(backend)

class EqOpsPresetsTest {
    private val state = WinampState()
    private val backend = RecordingEqBackend()
    private val store = InMemoryEqPresetStore()
    private val ops = EqOps(state, PlayerFacade(backend), store)

    private fun submenu(label: String) =
        ops
            .presetsMenu()
            .items
            .filterIsInstance<AmpMenuItem.Submenu>()
            .first { it.label == label }

    private fun actions(sub: AmpMenuItem.Submenu) = sub.items.filterIsInstance<AmpMenuItem.Action>()

    /** Clicks [entry] in [submenu] and returns the dialog it opens. */
    private fun picker(
        submenu: String,
        entry: String,
    ): PresetPicker {
        actions(submenu(submenu)).first { it.label == entry }.onClick()
        return state.presetPicker ?: error("$submenu > $entry opened no dialog")
    }

    @Test
    fun `applying a preset sets every band and the preamp, and pushes dB to the backend`() {
        val rock = EqFactoryPresets.ALL.first { it.name == "Rock" }
        ops.applyPreset(rock)
        assertEquals(rock.bands, state.eqBands.toList())
        assertEquals(rock.preamp, state.preamp)
        val settings = backend.pushed.last()
        // the backend receives the band in dB
        assertEquals(EqOps.eqDb(rock.bands[3]), settings.bandsDb[3], 1e-4f)
    }

    @Test
    fun `load offers Winamp's three entries, and its dialog holds the 17 presets`() {
        assertEquals(listOf("Preset...", EqOps.AUTO_LABEL, "Default"), actions(submenu("Load")).map { it.label })

        val dialog = picker("Load", "Preset...")

        assertEquals(EqFactoryPresets.ALL.map { it.name }, dialog.entries.map { it.label })
        assertEquals("Load", dialog.confirmLabel)
        assertEquals("the load dialog takes one preset", false, dialog.multiSelect)
    }

    @Test
    fun `picking one in the load dialog applies it`() {
        val dialog = picker("Load", "Preset...")
        val club = EqFactoryPresets.ALL.first { it.name == "Club" }

        dialog.onConfirm(listOf("Club"))

        assertEquals(club.bands, state.eqBands.toList())
    }

    @Test
    fun `save preset opens the name prompt and submitting persists the current settings`() {
        state.eqBands[0] = 50
        state.preamp = 40
        actions(submenu("Save")).first { it.label == "Preset..." }.onClick()
        val prompt = state.namePrompt
        assertNotNull(prompt)
        prompt!!.onSubmit("My sound")

        val saved = store.userPresets().single()
        assertEquals("My sound", saved.name)
        assertEquals(50, saved.bands[0])
        assertEquals(40, saved.preamp)
    }

    @Test
    fun `a saved preset shows in the load dialog, and delete removes it`() {
        store.save(EqPreset("Custom", 31, List(10) { 31 }))
        assertTrue(picker("Load", "Preset...").entries.any { it.label == "Custom" })
        state.presetPicker = null

        val delete = picker("Delete", "Preset...")
        delete.onConfirm(listOf("Custom"))

        assertTrue(store.userPresets().isEmpty())
    }

    @Test
    fun `the delete dialog says so when nothing is saved`() {
        val delete = picker("Delete", "Preset...")

        assertTrue(delete.entries.isEmpty())
        assertEquals("You have not saved a preset yet", delete.emptyMessage)
        assertTrue("the delete dialog takes several presets at once", delete.multiSelect)
    }

    @Test
    fun `load default applies flat until the user saves their own default`() {
        state.eqBands[5] = 60
        actions(submenu("Load")).first { it.label == "Default" }.onClick()
        assertEquals(List(10) { EqOps.CENTER }, state.eqBands.toList())

        state.eqBands[5] = 60
        state.preamp = 20
        actions(submenu("Save")).first { it.label == "Default" }.onClick()
        ops.resetBands()
        actions(submenu("Load")).first { it.label == "Default" }.onClick()
        assertEquals(60, state.eqBands[5])
        assertEquals(20, state.preamp)
    }
}
