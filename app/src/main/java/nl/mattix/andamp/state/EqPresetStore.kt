// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences

/**
 * User-saved EQ presets (Winamp's winamp.q1 role) plus the overwritable
 * default, and the per-track curves AUTO loads (its winamp.q2 role).
 */
interface EqPresetStore {
    /** Saved presets sorted by name; factory presets are not stored here. */
    fun userPresets(): List<EqPreset>

    /** Upserts by name. */
    fun save(preset: EqPreset)

    fun delete(name: String)

    /** Save > Default / Load > Default target; null until the user saves one. */
    var defaultPreset: EqPreset?

    /**
     * The curves AUTO loads, one per track (Winamp's winamp.q2), keyed by
     * [nl.mattix.andamp.core.model.TrackKey].
     */
    val autoPresets: PerTrackStore<EqPreset>
}

/**
 * One-line-per-preset text encoding: `preamp|b1,…,b10|name`. The name goes last so it may
 * contain the separator (split with a limit).
 */
object EqPresetCodec {
    private const val SEPARATOR = "|"
    private const val FIELDS = 3
    private const val BAND_COUNT = 10

    fun encode(preset: EqPreset): String =
        listOf(preset.preamp.toString(), preset.bands.joinToString(","), preset.name).joinToString(SEPARATOR)

    fun decode(encoded: String): EqPreset? {
        val parts = encoded.split(SEPARATOR, limit = FIELDS)
        if (parts.size != FIELDS || parts[2].isEmpty()) return null
        val preamp = parts[0].toIntOrNull() ?: return null
        val tokens = parts[1].split(",")
        val bands = tokens.mapNotNull { it.toIntOrNull() }
        return if (tokens.size == BAND_COUNT && bands.size == BAND_COUNT) EqPreset(parts[2], preamp, bands) else null
    }
}

class PrefsEqPresetStore(
    context: Context,
) : EqPresetStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("eq_presets", Context.MODE_PRIVATE)

    override fun userPresets(): List<EqPreset> =
        prefs.all.keys
            .filter { it.startsWith(PRESET_PREFIX) }
            .mapNotNull { key -> (prefs.getString(key, null))?.let(EqPresetCodec::decode) }
            .sortedBy { it.name.lowercase() }

    override fun save(preset: EqPreset) {
        prefs.edit().putString(PRESET_PREFIX + preset.name, EqPresetCodec.encode(preset)).apply()
    }

    override fun delete(name: String) {
        prefs.edit().remove(PRESET_PREFIX + name).apply()
    }

    override val autoPresets: PerTrackStore<EqPreset> =
        PrefsPerTrackStore(
            prefs,
            AUTO_PREFIX,
            EqPresetCodec::encode,
            EqPresetCodec::decode,
            // listed by the preset's name
            order = { it.value.name },
        )

    override var defaultPreset: EqPreset?
        get() = prefs.getString(DEFAULT_KEY, null)?.let(EqPresetCodec::decode)
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(DEFAULT_KEY) else edit.putString(DEFAULT_KEY, EqPresetCodec.encode(value))
            edit.apply()
        }

    private companion object {
        const val PRESET_PREFIX = "p:"
        const val AUTO_PREFIX = "a:"
        const val DEFAULT_KEY = "default"
    }
}
