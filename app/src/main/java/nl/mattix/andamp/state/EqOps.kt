// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.TrackKey
import nl.mattix.andamp.core.model.displayName
import nl.mattix.andamp.core.player.PlayerFacade

/**
 * Equalizer intents: every slider change updates the render state and pushes the full
 * settings to the backend (which ignores them without
 * [nl.mattix.andamp.core.model.Capabilities.hasEqualizer]).
 */
class EqOps(
    private val state: WinampState,
    private val facade: PlayerFacade,
    private val presets: EqPresetStore,
) {
    fun setBand(
        index: Int,
        value: Int,
    ) {
        state.eqBands[index] = value.coerceIn(0, 63)
        push()
    }

    fun setPreamp(value: Int) {
        state.preamp = value.coerceIn(0, 63)
        push()
    }

    fun toggleOn() {
        state.eqOn = !state.eqOn
        push()
    }

    /** Sets every band to 0 dB; double-tapping the 0db label does this. */
    fun resetBands() {
        for (i in state.eqBands.indices) state.eqBands[i] = CENTER
        push()
    }

    /**
     * Winamp's AUTO: a track that has a curve of its own gets it as it loads, and every
     * other track gets the saved Default. With no Default saved, the equalizer is left as
     * it is. Turning AUTO on mid-song changes nothing until the next track loads.
     *
     * The fallback to Default follows the Winamp Lite 2.72 manual
     * (winamp_manual_equalizer.html, mirrored at oocities.org/mcocrocks/operation/equalizer)
     * and mywinamp.com's equalizer guide.
     */
    fun trackChanged(track: Track?) {
        if (!state.eqAuto || track == null) return
        val own = presets.autoPresets.get(TrackKey.of(track))
        (own ?: presets.defaultPreset)?.let(::applyPreset)
    }

    /**
     * Save > Auto-load preset: stores the current curve for the current track under
     * [name], which the delete list shows; the track's display name by default.
     */
    fun saveAutoPreset(name: String? = null) {
        val track = state.currentTrack ?: return
        presets.autoPresets.put(TrackKey.of(track), currentAsPreset(name ?: track.displayName))
    }

    fun applyPreset(preset: EqPreset) {
        for (i in state.eqBands.indices) state.eqBands[i] = preset.bands[i].coerceIn(0, 63)
        state.preamp = preset.preamp.coerceIn(0, 63)
        push()
    }

    /**
     * The PRESETS button menu: Load / Save / Delete, per the Winamp 2.x manual. The
     * "Preset..." entries open a list dialog.
     */
    fun presetsMenu(anchor: MenuAnchor? = null): AmpMenu {
        val load =
            listOf(
                AmpMenuItem.Action("Preset...") { state.presetPicker = loadPicker() },
                autoLoadEntry(),
                AmpMenuItem.Action("Default") { applyPreset(presets.defaultPreset ?: EqFactoryPresets.FLAT) },
            )
        val save =
            listOf(
                AmpMenuItem.Action("Preset...") {
                    state.namePrompt =
                        NamePrompt(title = "Save preset") { name ->
                            presets.save(currentAsPreset(name))
                        }
                },
                // prompts for a name, with the track's display name filled in
                AmpMenuItem.Action(AUTO_LABEL, enabled = state.currentTrack != null) {
                    val track = state.currentTrack ?: return@Action
                    state.namePrompt =
                        NamePrompt(title = "Save auto-load preset", initial = track.displayName) { name ->
                            saveAutoPreset(name)
                        }
                },
                AmpMenuItem.Action("Default") { presets.defaultPreset = currentAsPreset("Default") },
            )
        val delete =
            listOf(
                AmpMenuItem.Action("Preset...") { state.presetPicker = deletePresetsPicker() },
                AmpMenuItem.Action("$AUTO_LABEL...") { state.presetPicker = deleteAutoPicker() },
            )
        return AmpMenu(
            "Equalizer presets",
            listOf(
                AmpMenuItem.Submenu("Load", load),
                AmpMenuItem.Submenu("Save", save),
                AmpMenuItem.Submenu("Delete", delete),
            ),
            anchor,
        )
    }

    /** Load > Auto-load preset: applies this track's own curve, whether or not AUTO is on. */
    private fun autoLoadEntry(): AmpMenuItem.Action {
        val own = state.currentTrack?.let { presets.autoPresets.get(TrackKey.of(it)) }
        return AmpMenuItem.Action(AUTO_LABEL, enabled = own != null) { own?.let(::applyPreset) }
    }

    /** The factory presets plus the listener's own. */
    private fun loadPicker(): PresetPicker {
        val user = presets.userPresets()
        val all = EqFactoryPresets.ALL.map { it to "Factory" } + user.map { it to "Saved" }
        return PresetPicker(
            title = "Load preset",
            entries = all.map { (preset, kind) -> PresetEntry(preset.name, preset.name, kind) },
            confirmLabel = "Load",
        ) { keys ->
            val name = keys.firstOrNull() ?: return@PresetPicker
            all.map { it.first }.firstOrNull { it.name == name }?.let(::applyPreset)
        }
    }

    private fun deletePresetsPicker(): PresetPicker {
        val user = presets.userPresets()
        return PresetPicker(
            title = "Delete presets",
            entries = user.map { PresetEntry(it.name, it.name) },
            confirmLabel = "Delete",
            multiSelect = true,
            destructive = true,
            emptyMessage = "You have not saved a preset yet",
        ) { keys -> keys.forEach(presets::delete) }
    }

    private fun deleteAutoPicker(): PresetPicker {
        val saved = presets.autoPresets.all()
        return PresetPicker(
            title = "Delete auto-load presets",
            entries = saved.map { PresetEntry(it.key, it.value.name) },
            confirmLabel = "Delete",
            multiSelect = true,
            destructive = true,
            emptyMessage = "No song has a preset of its own yet",
        ) { keys -> keys.forEach(presets.autoPresets::remove) }
    }

    private fun currentAsPreset(name: String) = EqPreset(name, state.preamp, state.eqBands.toList())

    private fun push() {
        facade.setEqualizer(
            EqSettings(
                enabled = state.eqOn,
                preampDb = eqDb(state.preamp),
                bandsDb = state.eqBands.map(::eqDb),
            ),
        )
    }

    companion object {
        /** The 0 dB position, where the factory presets put a flat band. */
        const val CENTER = 32

        /** The cut in dB at slider value 0; a step is `RANGE_DB / CENTER` dB. */
        const val RANGE_DB = 20f

        /** Winamp's wording for a curve that belongs to one track. */
        const val AUTO_LABEL = "Auto-load preset"

        /** Winamp slider 0..63 onto dB, with [CENTER] at 0. */
        fun eqDb(value: Int): Float = (value - CENTER) / CENTER.toFloat() * RANGE_DB
    }
}
