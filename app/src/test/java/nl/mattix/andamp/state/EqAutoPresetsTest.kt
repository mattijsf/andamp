// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Winamp's AUTO: a track that has an auto-load preset gets it as it loads, one without
 * gets the saved Default, and with neither the curve is left alone. Curves are filed
 * under [TrackKey].
 */
class EqAutoPresetsTest {
    private val state = WinampState()
    private val store = InMemoryEqPresetStore()
    private val ops = EqOps(state, playerFacadeFor(RecordingEqBackend()), store)

    private val track = Track("row-1", "Muse", "Hexagons", 60_000, uri = "content://media/audio/9")

    private fun curve(vararg bands: Int) = EqPreset("Muse - Hexagons", 40, bands.toList())

    private val saved = curve(10, 20, 30, 40, 50, 60, 61, 62, 63, 1)

    @Test
    fun `loading a track applies its own preset`() {
        store.autoPresets.put(TrackKey.of(track), saved)
        state.eqAuto = true

        ops.trackChanged(track)

        assertEquals(saved.bands, state.eqBands.toList())
        assertEquals(saved.preamp, state.preamp)
    }

    @Test
    fun `a track with nothing saved falls back to the default preset`() {
        // a saved Default loads for every track that has no curve of its own
        store.defaultPreset = EqPreset("Default", 20, List(10) { 21 })
        state.eqAuto = true
        ops.setBand(0, 63)

        ops.trackChanged(track)

        assertEquals(21, state.eqBands[0])
        assertEquals(20, state.preamp)
    }

    @Test
    fun `with no default saved either, the curve is left alone`() {
        state.eqAuto = true
        ops.setBand(0, 63)

        ops.trackChanged(track)

        assertEquals(63, state.eqBands[0])
    }

    @Test
    fun `a track's own curve wins over the default`() {
        store.defaultPreset = EqPreset("Default", 20, List(10) { 21 })
        store.autoPresets.put(TrackKey.of(track), saved)
        state.eqAuto = true

        ops.trackChanged(track)

        assertEquals(saved.bands, state.eqBands.toList())
    }

    @Test
    fun `with AUTO off a saved preset is left where it is`() {
        store.autoPresets.put(TrackKey.of(track), saved)
        ops.setBand(0, 5)

        ops.trackChanged(track)

        assertEquals(5, state.eqBands[0])
    }

    @Test
    fun `saving the curve keeps it under the track that is playing`() {
        state.playlist = listOf(track)
        state.currentIndex = 0
        ops.setBand(3, 61)

        ops.saveAutoPreset()

        assertEquals(
            61,
            store.autoPresets
                .get(TrackKey.of(track))
                ?.bands
                ?.get(3),
        )
    }

    @Test
    fun `a saved curve is named after the track's artist and title`() {
        playing()

        ops.saveAutoPreset()

        assertEquals(
            "Muse - Hexagons",
            store.autoPresets
                .all()
                .single()
                .value.name,
        )
    }

    @Test
    fun `deleting one leaves the rest`() {
        store.autoPresets.put("a", saved)
        store.autoPresets.put("b", saved)

        store.autoPresets.remove("a")

        assertNull(store.autoPresets.get("a"))
        assertEquals(
            "b",
            store.autoPresets
                .all()
                .single()
                .key,
        )
    }

    private fun submenu(label: String) =
        ops
            .presetsMenu()
            .items
            .filterIsInstance<AmpMenuItem.Submenu>()
            .first { it.label == label }

    private fun actions(sub: AmpMenuItem.Submenu) = sub.items.filterIsInstance<AmpMenuItem.Action>()

    private fun playing() {
        state.playlist = listOf(track)
        state.currentIndex = 0
    }

    @Test
    fun `load offers this track's own curve once it has one`() {
        playing()
        store.autoPresets.put(TrackKey.of(track), saved)

        val entry = actions(submenu("Load")).first { it.label == AUTO_LABEL }
        entry.onClick()

        assertEquals(saved.bands, state.eqBands.toList())
    }

    @Test
    fun `a track with nothing saved shows the load entry disabled`() {
        playing()

        val entry = actions(submenu("Load")).first { it.label == AUTO_LABEL }

        assertEquals(false, entry.enabled)
    }

    @Test
    fun `save prompts with the track's name and files the curve under it`() {
        playing()
        ops.setBand(2, 7)

        actions(submenu("Save")).first { it.label == AUTO_LABEL }.onClick()
        // the prompt opens with the track's name filled in
        val prompt = state.namePrompt ?: error("no prompt")
        assertEquals("Muse - Hexagons", prompt.initial)
        prompt.onSubmit("Loud room")

        assertEquals(
            7,
            store.autoPresets
                .get(TrackKey.of(track))
                ?.bands
                ?.get(2),
        )
        assertEquals("Loud room", store.autoPresets.get(TrackKey.of(track))?.name)
    }

    @Test
    fun `with nothing playing the save entry is disabled`() {
        state.playlist = emptyList()

        assertEquals(false, actions(submenu("Save")).first { it.label == AUTO_LABEL }.enabled)
    }

    @Test
    fun `the delete dialog lists every song that has a curve, by name`() {
        store.autoPresets.put(TrackKey.of(track), saved)

        actions(submenu("Delete")).first { it.label == "$AUTO_LABEL..." }.onClick()
        val dialog = state.presetPicker ?: error("no dialog")

        assertEquals(listOf("Muse - Hexagons"), dialog.entries.map { it.label })
        dialog.onConfirm(dialog.entries.map { it.key })

        assertNull(store.autoPresets.get(TrackKey.of(track)))
    }

    private companion object {
        const val AUTO_LABEL = "Auto-load preset"
        const val AUTO_LIST_LABEL = "Auto-load presets"
    }
}
