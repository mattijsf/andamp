// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/** An [EqPresetStore] kept in memory, for tests. */
class InMemoryEqPresetStore : EqPresetStore {
    private val presets = mutableMapOf<String, EqPreset>()
    override var defaultPreset: EqPreset? = null

    override fun userPresets(): List<EqPreset> = presets.values.sortedBy { it.name.lowercase() }

    override fun save(preset: EqPreset) {
        presets[preset.name] = preset
    }

    override fun delete(name: String) {
        presets.remove(name)
    }

    override val autoPresets: PerTrackStore<EqPreset> = InMemoryPerTrackStore { it.value.name }
}
