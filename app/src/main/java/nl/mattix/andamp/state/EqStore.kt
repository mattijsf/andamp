// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences

/** What the equalizer was left set to: its two buttons and the curve on its sliders. */
data class EqMemory(
    val on: Boolean,
    val auto: Boolean,
    /** 0..63, as [WinampState.preamp]. */
    val preamp: Int,
    /** One 0..63 value per band, as [WinampState.eqBands]. */
    val bands: List<Int>,
)

/**
 * Remembers the equalizer as it stands: ON, AUTO, the preamp and the ten bands.
 *
 * The presets a listener saved by name are in [EqPresetStore].
 */
class EqStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Null until an equalizer has been stored, and when what is stored cannot be read. */
    fun load(): EqMemory? {
        val curve = prefs.getString(CURVE, null)?.let(EqPresetCodec::decode) ?: return null
        return EqMemory(
            on = prefs.getBoolean(ON, true),
            auto = prefs.getBoolean(AUTO, false),
            preamp = curve.preamp,
            bands = curve.bands,
        )
    }

    fun save(memory: EqMemory) {
        prefs
            .edit()
            .putBoolean(ON, memory.on)
            .putBoolean(AUTO, memory.auto)
            .putString(CURVE, EqPresetCodec.encode(EqPreset(CURVE, memory.preamp, memory.bands)))
            .apply()
    }

    private companion object {
        const val PREFS = "equalizer"
        const val ON = "on"
        const val AUTO = "auto"
        const val CURVE = "curve"
    }
}
